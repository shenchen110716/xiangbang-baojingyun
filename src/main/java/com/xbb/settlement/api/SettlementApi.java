package com.xbb.settlement.api;

import com.xbb.settlement.internal.Settlement;
import java.util.List;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SettlementApi {

    record SettlementView(long id, long applicationId, long jobId, long workerUserId,
                           long amountCents, Settlement.Status status, String voidReason) { }

    void voidSettlement(long settlementId, String reason, long callerUserId);

    Optional<SettlementView> findById(long settlementId);

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

    /** 岗位当前生效的方案。没有则返回空。 */
    Optional<PayPlanView> activePayPlan(long jobId, long callerUserId);
}
