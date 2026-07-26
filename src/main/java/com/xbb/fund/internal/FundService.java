package com.xbb.fund.internal;

import com.xbb.fund.api.AccountType;
import com.xbb.fund.api.DisbursementStatus;
import com.xbb.fund.api.FundApi;
import com.xbb.fund.api.FundsDisbursed;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ApplicationEventPublisher events;

    FundService(PayoutRepository payouts, DisbursementRepository disbursements,
                 DisbursementChannel channel, EscrowService escrow,
                 ApplicationEventPublisher events) {
        this.payouts = payouts;
        this.disbursements = disbursements;
        this.channel = channel;
        this.escrow = escrow;
        this.events = events;
    }

    @Override
    @Transactional("fundTransactionManager")
    public void disburse(long payoutId) {
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
    public void retryDisbursement(long payoutId) {
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
            events.publishEvent(new FundsDisbursed(
                    payout.getId(), payout.getSettlementId(), payout.getPayeeUserId(), amount, Instant.now()));
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
    @Transactional(transactionManager = "fundTransactionManager", readOnly = true)
    public long balanceOf(AccountType accountType) {
        return escrow.balanceOf(accountType);
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
