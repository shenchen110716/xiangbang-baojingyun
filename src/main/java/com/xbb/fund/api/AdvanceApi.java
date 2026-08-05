package com.xbb.fund.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 借支与还款(老系统 M8「借押保」)。
 *
 * <p>平台先把钱垫给工人,之后从工资里逐笔扣回。三条规则来自老系统:
 * 借支不超可用额度、每笔留痕、发工资时**从工资直接抵扣**。
 */
public interface AdvanceApi {

    record AdvanceView(long id, long workerUserId, long amountCents, long outstandingCents,
                       String status, String reason, Instant createdAt, Instant clearedAt) { }

    record RepaymentView(long id, long advanceId, long amountCents, String source,
                         Long settlementId, Instant createdAt) { }

    /** 批一笔借支。要平台运维 —— 这是平台垫钱,不是工人自助。 */
    long grantAdvance(long workerUserId, long amountCents, String reason, long callerUserId);

    /** 登记线下还款。 */
    void recordManualRepayment(long advanceId, long amountCents, long callerUserId);

    /** 撤销。只有一分钱都没还过的才能撤。 */
    void cancelAdvance(long advanceId, long callerUserId);

    /** 某人未还合计。发工资抵扣和额度校验都用它。 */
    long outstandingOf(long workerUserId);

    List<AdvanceView> listMine(long workerUserId);

    /** 查某人的借支。本人或平台运维,其他人得到空列表。 */
    List<AdvanceView> listOf(long workerUserId, long callerUserId);

    Optional<AdvanceView> findById(long advanceId, long callerUserId);

    List<RepaymentView> repaymentsOf(long advanceId, long callerUserId);
}
