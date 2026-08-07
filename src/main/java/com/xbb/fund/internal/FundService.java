package com.xbb.fund.internal;

import com.xbb.fund.api.AccountType;
import com.xbb.fund.api.DisbursementStatus;
import com.xbb.fund.api.FundApi;
import com.xbb.fund.api.FundsDisbursed;
import com.xbb.fund.api.GuaranteeContext;
import com.xbb.fund.api.GuaranteeDecision;
import com.xbb.fund.api.GuaranteePolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import com.xbb.identity.api.IdentityApi;
import com.xbb.identity.api.Role;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
class FundService implements FundApi {

    /** 资金链路此前**全程零日志**。钱出了问题时,没有任何东西可查。 */
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FundService.class);

    private final PayoutRepository payouts;
    private final DisbursementRepository disbursements;
    private final DisbursementChannel channel;
    private final EscrowService escrow;
    private final GuaranteePolicy guaranteePolicy;
    private final WorkerCreditRepository credits;
    private final FundOutboxRepository outbox;
    private final ObjectMapper json;
    private final IdentityApi identityApi;
    /** 判断"这个人是不是这家单位的法人代表"。**现查不缓存**(铁律 5)。 */
    private final com.xbb.org.api.OrgApi orgApi;
    /** 自身代理:三段式里每一段都要各自的事务边界,自调用会绕过代理。 */
    private final ObjectProvider<FundService> self;

    FundService(PayoutRepository payouts, DisbursementRepository disbursements,
                 DisbursementChannel channel, EscrowService escrow,
                 GuaranteePolicy guaranteePolicy, WorkerCreditRepository credits,
                 FundOutboxRepository outbox, ObjectMapper json,
                       IdentityApi identityApi,
                 com.xbb.org.api.OrgApi orgApi,
                 ObjectProvider<FundService> self) {
        this.payouts = payouts;
        this.disbursements = disbursements;
        this.channel = channel;
        this.escrow = escrow;
        this.guaranteePolicy = guaranteePolicy;
        this.credits = credits;
        this.outbox = outbox;
        this.json = json;
        this.identityApi = identityApi;
        this.orgApi = orgApi;
        this.self = self;
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

    /**
     * 代发。**注意本方法没有 @Transactional**——它分三段,中间那段要在事务之外。
     *
     * <p>原来整段在一个事务里:先 escrow.debit(只改内存,@Version 要到提交时才校验),
     * 再调真实资金通道。两笔并发时双方都读到同一个 version,**两笔钱都真的打出去了**,
     * 然后其中一笔在提交时撞乐观锁全部回滚——账上"什么都没发生",实际少了一笔钱,
     * 运营看到余额不对很可能再点一次发放。
     *
     * <p>现在:①事务内预扣并落 PENDING 代发单(此时乐观锁已校验、已提交);
     * ②事务外调通道;③事务内记结果。通道调用发生时,资金已经被真正扣住,
     * 不存在"两笔都以为自己能扣"的窗口。
     */
    @Override
    public void disburse(long payoutId, long callerUserId) {
        requirePlatformOps(callerUserId);
        Disbursement prepared = self.getObject().reserveForDisbursement(payoutId);
        if (prepared == null) {
            log.info("代发跳过:payout={} 已成功代发过", payoutId);
            return;   // 已成功代发过,幂等
        }
        log.info("代发开始:payout={} 收款人={} 金额={}分 幂等键={}",
                payoutId, prepared.getPayeeUserId(), prepared.getAmountCents(), prepared.getIdempotencyKey());
        callChannelAndRecord(prepared);
    }

    @Override
    public void retryDisbursement(long payoutId, long callerUserId) {
        requirePlatformOps(callerUserId);
        Disbursement prepared = self.getObject().reserveForRetry(payoutId);
        callChannelAndRecord(prepared);
    }

    /** ①预扣:乐观锁在这一段提交时就校验完,通道调用不再和它抢。 */
    @Transactional("fundTransactionManager")
    public Disbursement reserveForDisbursement(long payoutId) {
        Payout payout = payouts.findById(payoutId)
                .orElseThrow(() -> new IllegalArgumentException("发放记录不存在"));
        // **状态检查排在动钱之前。**原来它在 Payout.markPaid 里,也就是扣完款才拦 ——
        // 事务会回滚所以不会丢钱,但报出来的是扣款那一步的错(比如"余额不足"),
        // 而真正的原因是"这笔已作废"。运营照着"余额不足"去备资,备完还是发不出去
        if (payout.getStatus() == Payout.Status.CANCELLED) {
            throw new IllegalStateException("该结算已作废,不能发放");
        }
        Optional<Disbursement> existing = disbursements.findByPayoutId(payoutId);
        if (existing.isPresent() && existing.get().getStatus() == Disbursement.Status.SUCCESS) {
            return null;
        }
        Disbursement disbursement = existing.orElseGet(() -> disbursements.save(new Disbursement(
                payoutId, payout.getPayeeUserId(), payout.getAmountCents(), idempotencyKeyFor(payoutId))));
        // **从出资单位的账户扣。**payout.orgId 为 null 时扣平台账户 ——
        // 老的代发单没有这个信息,那时的行为就是从平台账户出
        escrow.debit(payout.getOrgId(), AccountType.USER_FUNDS, disbursement.getAmountCents(),
                "代发预扣 payout#" + payoutId, "disburse-" + payoutId);
        return disbursement;
    }

    @Transactional("fundTransactionManager")
    public Disbursement reserveForRetry(long payoutId) {
        Disbursement disbursement = disbursements.findByPayoutId(payoutId)
                .orElseThrow(() -> new IllegalArgumentException("代发记录不存在"));
        if (disbursement.getStatus() == Disbursement.Status.SUCCESS) {
            throw new IllegalStateException("该笔代发已成功,无需重发");
        }
        Payout payout = payouts.findById(payoutId)
                .orElseThrow(() -> new IllegalArgumentException("发放记录不存在"));
        disbursement.recordRetry();
        disbursements.save(disbursement);
        // 上一轮失败时已经原路退回,这里要重新预扣。重试用不同的幂等键,
        // 否则第二次预扣会被当成重复而跳过,钱没扣就把款打出去了。
        escrow.debit(payout.getOrgId(), AccountType.USER_FUNDS, disbursement.getAmountCents(),
                "代发预扣(重试) payout#" + payoutId,
                "disburse-" + payoutId + "-retry-" + disbursement.getRetryCount());
        return disbursement;
    }

    /** ②通道调用在事务之外:外部 IO 不该占着数据库事务和监管账户那一行的锁。 */
    private void callChannelAndRecord(Disbursement disbursement) {
        try {
            DisbursementChannel.Receipt receipt = channel.disburse(
                    disbursement.getIdempotencyKey(), disbursement.getPayeeUserId(),
                    disbursement.getAmountCents(), DisbursementChannel.PayeeAccount.WECHAT_BALANCE);
            self.getObject().recordSuccess(disbursement.getId(), receipt);
            log.info("代发成功:payout={} 金额={}分 外部单号={}",
                    disbursement.getPayoutId(), disbursement.getAmountCents(), receipt.externalRef());
        } catch (DisbursementChannel.ChannelException e) {
            // ERROR 而非 WARN:代发失败意味着有人的工资没到账,这不该淹没在日常噪音里
            log.error("代发失败,已原路退回:payout={} 金额={}分 原因={}",
                    disbursement.getPayoutId(), disbursement.getAmountCents(), e.getMessage(), e);
            self.getObject().recordFailure(disbursement.getId(), e.getMessage());
        }
    }

    /** ③记结果:成功。资金已在①预扣,这里只落状态与事件。 */
    @Transactional("fundTransactionManager")
    public void recordSuccess(long disbursementId, DisbursementChannel.Receipt receipt) {
        Disbursement disbursement = disbursements.findById(disbursementId).orElseThrow();
        Payout payout = payouts.findById(disbursement.getPayoutId()).orElseThrow();

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
                        payout.getPayeeUserId(), disbursement.getAmountCents(), Instant.now()))));
    }

    /**
     * ③记结果:失败,**原路退回**。
     *
     * <p>账本上既留下预扣也留下冲正,对账时能看出发生过什么,
     * 而不是"什么都没发生"(§6.4.2:对账以账本为准)。
     */
    @Transactional("fundTransactionManager")
    public void recordFailure(long disbursementId, String error) {
        Disbursement disbursement = disbursements.findById(disbursementId).orElseThrow();
        // **必须退回扣款的那一家。**退错的话 A 家的钱进了 B 家的账户,
        // 两边余额都对不上,而且这种错不会报任何异常 —— 只有对账才看得出来
        Long orgId = payouts.findById(disbursement.getPayoutId())
                .map(Payout::getOrgId).orElse(null);
        escrow.credit(orgId, AccountType.USER_FUNDS, disbursement.getAmountCents(),
                "代发失败冲正 payout#" + disbursement.getPayoutId(), null);
        disbursement.markFailed(error);
        disbursements.save(disbursement);
    }

    @Override
    @Transactional(transactionManager = "fundTransactionManager", readOnly = true)
    public java.util.List<PayoutView> listByOrg(long orgId, long callerUserId) {
        if (!orgApi.isLegalRepOf(orgId, callerUserId)
                && !identityApi.hasRole(callerUserId, Role.PLATFORM_OPS)) {
            // 列表接口挡住路人时回 200 [] 是对的(铁律 5.1)
            return java.util.List.of();
        }
        return payouts.findByOrgIdOrderByIdDesc(orgId).stream().map(this::toView).toList();
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
    public void topUp(AccountType accountType, long amountCents, String reason,
                      String idempotencyKey, long callerUserId) {
        requirePlatformOps(callerUserId);
        if (amountCents <= 0) {
            throw new IllegalArgumentException("入账金额必须为正");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("入账必须带幂等键");
        }
        log.info("监管账户入账:账户={} 金额={}分 事由={} 幂等键={} 操作人={}",
                accountType, amountCents, reason, idempotencyKey, callerUserId);
        escrow.credit(accountType, amountCents, reason, idempotencyKey);
    }

    @Override
    @Transactional("fundTransactionManager")
    public void spendFromAccount(AccountType accountType, long amountCents, String reason,
                                  String idempotencyKey) {
        log.info("监管账户出账:账户={} 金额={}分 事由={} 幂等键={}",
                accountType, amountCents, reason, idempotencyKey);
        escrow.debit(accountType, amountCents, reason, idempotencyKey);
    }

    @Override
    @Transactional(transactionManager = "fundTransactionManager", readOnly = true)
    public long balanceOf(AccountType accountType, long callerUserId) {
        requirePlatformOps(callerUserId);
        return escrow.balanceOf(accountType);
    }

    @Override
    @Transactional("fundTransactionManager")
    public void topUpOrg(Long orgId, AccountType accountType, long amountCents, String reason,
                          String idempotencyKey, long callerUserId) {
        requireCanOperate(orgId, callerUserId, "给账户入账");
        if (amountCents <= 0) {
            throw new IllegalArgumentException("入账金额必须为正");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            // 充值重发一次就多一笔钱。幂等键是唯一能拦住的东西
            throw new IllegalArgumentException("入账必须带幂等键");
        }
        log.info("账户入账:单位={} 账户={} 金额={}分 事由={} 幂等键={} 操作人={}",
                orgId == null ? "平台" : orgId, accountType, amountCents,
                reason, idempotencyKey, callerUserId);
        escrow.credit(orgId, accountType, amountCents, reason, idempotencyKey);
    }

    @Override
    @Transactional(transactionManager = "fundTransactionManager", readOnly = true)
    public long orgBalanceOf(Long orgId, AccountType accountType, long callerUserId) {
        requireCanOperate(orgId, callerUserId, "查看账户余额");
        return escrow.balanceOf(orgId, accountType);
    }

    /**
     * 能不能动这个账户:这家单位的法人代表,或平台运维。
     *
     * <p><b>平台账户(orgId 为 null)只有平台运维能动。</b>
     *
     * <p>归属**现查不缓存**(铁律 5)。换了法人代表之后,旧的那个人
     * 不该还能从这家单位账上把钱发出去。
     */
    private void requireCanOperate(Long orgId, long callerUserId, String what) {
        if (orgId == null) {
            requirePlatformOps(callerUserId);
            return;
        }
        if (orgApi.isLegalRepOf(orgId, callerUserId)
                || identityApi.hasRole(callerUserId, Role.PLATFORM_OPS)) {
            return;
        }
        // 往别人账户里打钱看着像做好事,但那笔钱随后会被用来发薪,
        // 等于替别人承担了用工责任
        throw new org.springframework.security.access.AccessDeniedException(
                "只有单位 #" + orgId + " 的法人代表或平台运维可以" + what);
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
    public Optional<DisbursementView> findDisbursement(long payoutId, long callerUserId) {
        return disbursements.findByPayoutId(payoutId)
                .filter(d -> maySee(d.getPayeeUserId(), callerUserId))
                .map(d -> new DisbursementView(
                d.getId(), d.getPayoutId(), d.getPayeeUserId(), d.getAmountCents(),
                DisbursementStatus.valueOf(d.getStatus().name()), d.getExternalRef(),
                d.getTaxCertificateNo(), d.getFailReason(), d.getRetryCount()));
    }

    @Override
    @Transactional(transactionManager = "fundTransactionManager", readOnly = true)
    public Optional<PayoutView> findById(long payoutId, long callerUserId) {
        return payouts.findById(payoutId)
                .filter(p -> maySee(p.getPayeeUserId(), callerUserId))
                .map(this::toView);
    }

    /**
     * 谁看得到这笔钱:收款人本人,或平台运维。
     *
     * <p>**看不到时返回空而不是抛无权访问** —— 抛异常会顺带确认这条记录存在,
     * 拿编号数一遍就能数出平台发了多少笔款。
     */
    private boolean maySee(long payeeUserId, long callerUserId) {
        return payeeUserId == callerUserId || identityApi.hasRole(callerUserId, Role.PLATFORM_OPS);
    }

    @Override
    @Transactional(transactionManager = "fundTransactionManager", readOnly = true)
    public Optional<PayoutView> findBySettlementId(long settlementId) {
        return payouts.findBySettlementId(settlementId).map(this::toView);
    }

    @Override
    @Transactional(transactionManager = "fundTransactionManager", readOnly = true)
    public List<PayoutView> listMyPayouts(long payeeUserId) {
        return payouts.findByPayeeUserIdOrderByIdDesc(payeeUserId).stream().map(this::toView).toList();
    }

    private PayoutView toView(Payout p) {
        return new PayoutView(p.getId(), p.getSettlementId(), p.getPayeeUserId(),
                p.getAmountCents(), com.xbb.fund.api.PayoutStatus.valueOf(p.getStatus().name()), p.getOrgId());
    }
}
