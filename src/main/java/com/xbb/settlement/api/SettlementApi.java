package com.xbb.settlement.api;

import java.util.List;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SettlementApi {

    record SettlementView(long id, long applicationId, long jobId, long workerUserId,
                           long amountCents, SettlementStatus status, String voidReason) { }

    void voidSettlement(long settlementId, String reason, long callerUserId);

    /**
     * 按编号查工资单。**必须带 caller** —— 不带的话把编号从 1 数上去就是全站工资表。
     *
     * <p>看得到的只有两方:工人本人,和岗位所属组织的法人代表(要核对自己发出去的钱)。
     * 其他人一律返回空 —— 返回"无权访问"会顺带确认这张单存在。
     */
    Optional<SettlementView> findById(long settlementId, long callerUserId);

    /** 一行工资明细。 */
    record PayslipLine(String name, long amountCents) { }

    /**
     * 工资条:金额是怎么算出来的。
     *
     * <p>只给总额是不够的 —— 工人对不上的时候没有任何自查手段,只能来问人。
     *
     * @param payPlanId  按哪版方案算的。为空表示这单走的是岗位一口价(方案启用前的老口径)
     * @param lines      逐行明细,加起来等于应发。扣款是负数
     */
    record PayslipView(long id, long applicationId, long jobId, long workerUserId,
                       long amountCents, String status, String voidReason,
                       Long payPlanId, String payPlanName, String payType,
                       int minutes, int workDays, List<PayslipLine> lines) { }

    /** 工资条。可见范围同 {@link #findById}。 */
    Optional<PayslipView> payslip(long settlementId, long callerUserId);

    Optional<SettlementView> findByApplicationId(long applicationId);

    /** 我的工资单列表。查询条件即归属,不存在看到别人工资的路径。 */
    List<SettlementView> listMySettlements(long workerUserId);

    // ─────────────── 计薪方案 ───────────────

    record FactorSpec(String factorType, String name, long amountCents) { }

    record PayPlanView(long id, long jobId, int version, String name, String payType,
                       long basicSalaryCents, long floatSalaryCents, long fixedSalaryCents,
                       String status, LocalDate effectiveFrom, LocalDate effectiveTo,
                       List<FactorSpec> factors) { }

    /**
     * 新建/改版计薪方案。**方案版本不可变** —— 每次调用都是发一个新版本,
     * 岗位上原来生效的那版自动置为失效(不删除:已出的工资单还引用着它)。
     *
     * <p>只有岗位所属组织的法人代表能设。
     *
     * @return 新版本的方案 id
     */
    long publishPayPlan(long jobId, String name, String payType,
                        long basicSalaryCents, long floatSalaryCents, long fixedSalaryCents,
                        LocalDate effectiveFrom, List<FactorSpec> factors, long callerUserId);

    /** 岗位的全部方案版本,新的在前。 */
    List<PayPlanView> listPayPlans(long jobId, long callerUserId);

    /**
     * 这个人能不能看这个岗位的计薪方案(= 是不是岗位所属组织的法人代表)。
     *
     * <p><b>为什么要它。</b>「不是你的岗位」和「是你的岗位但还没设方案」
     * 必须能分开:前者按铁律 5.1 回 404,后者回 204 ——
     * 没方案是正常状态(按岗位一口价发),前端把它当故障显示的话,
     * 真正的故障就淹没在里面了。
     */
    boolean mayViewPayPlans(long jobId, long callerUserId);

    /** 岗位当前生效的方案。没有则返回空。 */
    Optional<PayPlanView> activePayPlan(long jobId, long callerUserId);
}
