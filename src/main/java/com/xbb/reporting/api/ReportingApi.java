package com.xbb.reporting.api;

import java.util.List;
import java.util.Map;

/** §6.6 多维盈亏报表。数据全部来自事件订阅建的本域宽表,不跨域 join。 */
public interface ReportingApi {

    enum Dimension { WORKER, BROKER, ORG }

    /** §6.6.2 分摊基准,后台可配、每类公共费用绑定一种。 */
    enum AllocationBasis { HEADCOUNT, REVENUE_SHARE, WORK_HOURS }

    record ProfitLoss(Dimension dimension, long dimensionId, long revenueCents,
                       long directCostCents, long allocatedOverheadCents, long profitCents) { }

    /** 记一笔公共费用,待分摊。 */
    long recordOverhead(String label, long amountCents, AllocationBasis basis);

    /**
     * 某维度的盈亏 = 收入 − 直接成本 − 分摊的公共费用(§6.6.2)。
     *
     * @param weights 分摊权重(按人数/营收占比/工时,由调用方按基准提供)。
     *                空表示不分摊公共费用。
     */
    List<ProfitLoss> profitAndLoss(Dimension dimension, Map<Long, Long> weights);
}
