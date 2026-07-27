package com.xbb.fund.internal;

import com.xbb.fund.api.AccountType;
import com.xbb.fund.api.DisbursementStatus;
import com.xbb.fund.api.FundApi;
import com.xbb.fund.api.FundsDisbursed;
import com.xbb.fund.api.GuaranteeContext;
import com.xbb.fund.api.GuaranteeDecision;
import com.xbb.fund.api.GuaranteePolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbb.identity.api.IdentityApi;
import com.xbb.identity.api.Role;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
class FundService implements FundApi {

    private final PayoutRepository payouts;
    private final DisbursementRepository disbursements;
    private final DisbursementChannel channel;
    private final EscrowService escrow;
    private final GuaranteePolicy guaranteePolicy;
    private final WorkerCreditRepository credits;
    private final FundOutboxRepository outbox;
    private final ObjectMapper json;
    private final IdentityApi identityApi;

    FundService(PayoutRepository payouts, DisbursementRepository disbursements,
                 DisbursementChannel channel, EscrowService escrow,
                 GuaranteePolicy guaranteePolicy, WorkerCreditRepository credits,
                 FundOutboxRepository outbox, ObjectMapper json,
                       IdentityApi identityApi) {
        this.payouts = payouts;
        this.disbursements = disbursements;
        this.channel = channel;
        this.escrow = escrow;
        this.guaranteePolicy = guaranteePolicy;
        this.credits = credits;
        this.outbox = outbox;
        this.json = json;
        this.identityApi = identityApi;
    }

    /**
     * 平台运维操作,要求 {@link Role#PLATFORM_OPS}。
     *
     * <p>这不是归属校验的替代品,而是它缺席时唯一说得通的东西:这几个动作的
     * "主人"是平台自己,不是某个用户。角色每次向身份域现查,不读 JWT 声明,
     * 这样收回权限立刻生效(理由同 OutboxOpsController)。
     */
    private void requirePlatformOps(long callerUserId) {
        if (!identityApi.hasRole(callerUserId, Role.PLATFORM_OPS)) {
            throw new AccessDeniedException("需要平台运维权限");
        }
    }

    private String serialize(Object event) {
        try {
            return json.writeValueAsString(event);
        } catch (Exception e) {
            // 序列化不了就别让这笔发放"成功"——事件发不出去,下游的佣金和账目就永远缺一笔
            throw new IllegalStateException("事件无法序列化: " + event, e);
        }
    }

    /** 新人起始信用分,和评价域 CreditCalculator.NEW_USER_SCORE 一致。 */
    private static final int NEW_USER_CREDIT = 60;

    @Override
    @Transactional("fundTransactionManager")
    public void disburse(long payoutId, long callerUserId) {
        requirePlatformOps(callerUserId);
        Payout payout = payouts.findById(payoutId)
                .orElseThrow(() -> new IllegalArgumentException("发放记录不存在"));
        // 幂等:同一笔 payout 已经成功代发过就不再动钱
        Optional<Disbursement> existing = disbursements.findByPayoutId(payoutId);
        if (existing.isPresent() && existing.get().getStatus() == Disbursement.Status.SUCCESS) {
            return;
        }
        Disbursement disbursement = existing.orElseGet(() -> disbursements.save(new Disbursement(
                payoutId, payout.getPayeeUserId(), payout.getAmountCents(), idempotencyKeyFor(payoutId))));
        execute(payout, disbursement);
    }

    @Override
    @Transactional("fundTransactionManager")
    public void retryDisbursement(long payoutId, long callerUserId) {
        requirePlatformOps(callerUserId);
        Disbursement disbursement = disbursements.findByPayoutId(payoutId)
                .orElseThrow(() -> new IllegalArgumentException("代发记录不存在"));
        if (disbursement.getStatus() == Disbursement.Status.SUCCESS) {
            throw new IllegalStateException("该笔代发已成功,无需重发");
        }
        Payout payout = payouts.findById(payoutId)
                .orElseThrow(() -> new IllegalArgumentException("发放记录不存在"));
        disbursement.recordRetry();
        execute(payout, disbursement);
    }

    /**
     * 先从监管账户预扣,再走通道。通道失败则**原路退回**并落 FAILED 记录——
     * 这样账本上既留下了预扣也留下了冲正,对账时能看出发生过什么,
     * 而不是"什么都没发生"(§6.4.2:对账以账本为准)。
     */
    private void execute(Payout payout, Disbursement disbursement) {
        long amount = disbursement.getAmountCents();
        escrow.debit(AccountType.USER_FUNDS, amount, "代发预扣 payout#" + payout.getId());
        try {
            DisbursementChannel.Receipt receipt = channel.disburse(
                    disbursement.getIdempotencyKey(), disbursement.getPayeeUserId(), amount,
                    DisbursementChannel.PayeeAccount.WECHAT_BALANCE);
            disbursement.markSuccess(receipt.externalRef(), receipt.taxCertificateNo());
            disbursements.save(disbursement);

            payout.disburse();
            payouts.save(payout);
            // 事件与账本变动**同事务落库**,再由中继投递。丢了这条事件的后果不会自愈:
            // 不会再有第二次"这笔钱发过了"的事件,佣金和盈亏账就永远少一笔。
            outbox.save(new FundOutboxEvent(
                    java.util.UUID.randomUUID().toString(),
                    FundsDisbursed.class.getName(),
                    serialize(new FundsDisbursed(payout.getId(), payout.getSettlementId(),
                            payout.getPayeeUserId(), amount, Instant.now()))));
        } catch (DisbursementChannel.ChannelException e) {
            escrow.credit(AccountType.USER_FUNDS, amount, "代发失败冲正 payout#" + payout.getId());
            disbursement.markFailed(e.getMessage());
            disbursements.save(disbursement);
        }
    }

    private static String idempotencyKeyFor(long payoutId) {
        return "payout-" + payoutId;
    }

    @Override
    @Transactional("fundTransactionManager")
    public void topUp(AccountType accountType, long amountCents, String reason) {
        escrow.credit(accountType, amountCents, reason);
    }

    @Override
    @Transactional("fundTransactionManager")
    public void spendFromAccount(AccountType accountType, long amountCents, String reason) {
        escrow.debit(accountType, amountCents, reason);
    }

    @Override
    @Transactional(transactionManager = "fundTransactionManager", readOnly = true)
    public long balanceOf(AccountType accountType) {
        return escrow.balanceOf(accountType);
    }

    @Override
    @Transactional(transactionManager = "fundTransactionManager", readOnly = true)
    public GuaranteeDecision decideGuarantee(long userId, long jobId, long jobSalaryCents) {
        int creditScore = credits.findById(userId)
                .map(c -> (int) Math.round(c.getScore()))
                .orElse(NEW_USER_CREDIT);
        return guaranteePolicy.decide(
                new GuaranteeContext(userId, jobId, creditScore, jobSalaryCents, 0));
    }

    @Override
    @Transactional(transactionManager = "fundTransactionManager", readOnly = true)
    public Optional<DisbursementView> findDisbursement(long payoutId) {
        return disbursements.findByPayoutId(payoutId).map(d -> new DisbursementView(
                d.getId(), d.getPayoutId(), d.getPayeeUserId(), d.getAmountCents(),
                DisbursementStatus.valueOf(d.getStatus().name()), d.getExternalRef(),
                d.getTaxCertificateNo(), d.getFailReason(), d.getRetryCount()));
    }

    @Override
    @Transactional(transactionManager = "fundTransactionManager", readOnly = true)
    public Optional<PayoutView> findById(long payoutId) {
        return payouts.findById(payoutId).map(this::toView);
    }

    @Override
    @Transactional(transactionManager = "fundTransactionManager", readOnly = true)
    public Optional<PayoutView> findBySettlementId(long settlementId) {
        return payouts.findBySettlementId(settlementId).map(this::toView);
    }

    private PayoutView toView(Payout p) {
        return new PayoutView(p.getId(), p.getSettlementId(), p.getPayeeUserId(), p.getAmountCents(), p.getStatus());
    }
}
