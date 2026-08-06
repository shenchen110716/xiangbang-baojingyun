package com.xbb.broker.internal;

/**
 * 总价模式下,一笔业务的钱怎么分成三段。
 *
 * <p>老板 2026-08-06 定的口径:
 * <pre>
 *   员工价     = 总价 − 佣金总额        (老板写作 总价 × (1 − 佣金比例),等价)
 *   佣金总额   = 总价 × 佣金比例        (比例按 类目 + 地区 配)
 *   派遣公司留存 = 佣金总额 × 派遣留存比例   (第三方持证派遣主体,独立收款方)
 *   服务站佣金总额 = 佣金总额 − 派遣公司留存
 * </pre>
 * 服务站那一笔再往下走现有的主动/被动/平台/服务站四档(CommissionSplitter)。
 *
 * <p><b>纯计算,不碰数据库。</b>钱的算法要能被单独按住看 ——
 * 混在带事务的服务里,每验一个边界都要起一次 Spring。
 */
record TotalPricePlan(long totalPriceCents, long workerCents, long commissionCents,
                      long dispatchRetainCents, long stationPoolCents) {

    /**
     * @param commissionPct     总价里有多少算佣金
     * @param dispatchRetainPct 佣金里派遣公司留多少
     */
    static TotalPricePlan of(long totalPriceCents, int commissionPct, int dispatchRetainPct) {
        if (totalPriceCents < 0) {
            throw new IllegalArgumentException("总价不能为负");
        }
        if (commissionPct < 0 || commissionPct > 100) {
            throw new IllegalArgumentException("佣金比例要在 0~100 之间,当前 " + commissionPct);
        }
        if (dispatchRetainPct < 0 || dispatchRetainPct > 100) {
            throw new IllegalArgumentException("派遣留存比例要在 0~100 之间,当前 " + dispatchRetainPct);
        }
        // **整数分,向下取整。**用 double 的话 0.1 + 0.2 那类误差会进到钱里,
        // 而对账时差一分钱和差一万块一样要查
        long commission = totalPriceCents * commissionPct / 100;
        long retain = commission * dispatchRetainPct / 100;
        // 服务站那笔用**减法**得出,不是再算一次百分比 ——
        // 两边各自取整的话,两段加起来会比佣金总额少一分,那一分谁也说不清去哪了
        long pool = commission - retain;
        // **员工价也用减法。**老板的公式写作 总价 × (1 − 佣金比例),
        // 数学上等价,但那样算会和佣金各自取整 —— 两段加起来可能不等于总价,
        // 而差出来的那一分钱在对账上是没人认领的
        long worker = totalPriceCents - commission;
        return new TotalPricePlan(totalPriceCents, worker, commission, retain, pool);
    }
}
