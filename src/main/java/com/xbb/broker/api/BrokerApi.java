package com.xbb.broker.api;

import com.xbb.broker.internal.Commission;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BrokerApi {

    record BrokerView(long userId, boolean registered) { }

    record CommissionView(long id, long brokerUserId, long workerUserId, long settlementId,
                           long amountCents, Commission.Status status) { }

    void registerBroker(long userId);

    long bindWorker(long brokerUserId, long workerUserId);

    void payCommission(long commissionId, long callerUserId);

    Optional<BrokerView> findBroker(long userId);

    /** 佣金明细。只有挣这笔佣金的经纪人本人或平台运维看得到。 */
    Optional<CommissionView> findCommission(long commissionId, long callerUserId);

    // ─────────────── 分享与业务员产生 ───────────────

    /**
     * 分享岗位/商品,拿到分享码。**同一个人重复分享同一个东西返回同一个码** ——
     * 每次新建的话,同一个人会有一堆等价的分享码,谁也说不清他带来了几单。
     */
    String share(long sharerUserId, String targetType, long targetId);

    /**
     * 记一条归因:某人通过分享码进来了。
     *
     * <p><b>归属唯一</b>:一个人只能归因给一个分享人,已归属的不会被改 ——
     * 允许改的话,两个人分享给同一个人时两边都算业绩,佣金会被重复计算。
     *
     * @return 是否新建了归因
     */
    boolean attributeShare(String code, long convertedUserId);

    /**
     * 站长授权某人成为业务员,直接挂在本站下。
     *
     * <p>**只有站长本人能授权。**这是在往自己站里加一个能分佣金的人。
     */
    void grantBroker(long stationOrgId, long userId, long callerUserId);

    record BrokerOriginView(long userId, String origin, Long sourceRef, Long grantedBy,
                            java.time.Instant createdAt) { }

    /** 这个人凭什么是业务员:自助注册、分享自动升级、还是站长授权。 */
    java.util.Optional<BrokerOriginView> brokerOrigin(long userId, long callerUserId);

    // ─────────────── 按业务类目的分成比例 ───────────────

    record StationRateView(Long stationOrgId, String category, int percent,
                           java.time.Instant updatedAt) { }

    /**
     * 某个类目下的一整套分配方案。
     *
     * <p>此前只有服务站那一档能按类目设,其余五档全局共用 —— 而岗位、商品、培训的
     * 分账结构本来就不同,用同一套比例去分,任何一个类目都是错的。
     */
    record SchemeView(Long stationOrgId, String category, int activePct, int platformPct,
                      int passivePct, int stationPct, int passiveStepPct,
                      long minPayoutCents, java.time.Instant updatedAt) { }

    /**
     * 设某个类目下的整套分配方案。{@code stationOrgId} 传 null 表示设平台默认。
     *
     * <p>三级取数:站点覆盖 → 平台默认 → 全局兜底参数。
     * **平台 + 被动 + 服务站在同一块"剩余"里分**,三者相加不能超过 100。
     */
    void setScheme(Long stationOrgId, String category, int activePct, int platformPct,
                   int passivePct, int stationPct, int passiveStepPct, long minPayoutCents,
                   String reason, long callerUserId);

    /** 某站已设的方案(传 null 看平台默认)。 */
    List<SchemeView> listSchemes(Long stationOrgId, long callerUserId);

    /**
     * 总价怎么分成三段(老板 2026-08-06 的公式):
     * <pre>
     *   员工价     = 总价 − 佣金总额
     *   佣金总额   = 总价 × 佣金比例(类目 + 地区)
     *   派遣公司留存 = 佣金总额 × 派遣留存比例
     *   服务站佣金总额 = 佣金总额 − 派遣公司留存
     * </pre>
     *
     * <p><b>发单时算一次、存下来,不在结算时再算。</b>
     * 工人是看着"这单 900 元"才接的 —— 中途有人改了比例,
     * 结算时重算会让他拿到手变成 850。承诺过的价钱不该被事后改动。
     *
     * <p>没配比例时**抛异常,不给默认值**:编一个数字出来就是拿别人的钱冒险。
     *
     * @param regionCode 国标行政区划代码,从细到粗回退到全国
     */
    TotalPriceSplit splitTotalPrice(String category, String regionCode, long totalPriceCents);

    /**
     * 配「类目 + 地区」的佣金比例。{@code regionCode} 传 null 表示全国兜底。
     *
     * <p><b>留了派遣比例就必须指定收款的派遣公司</b> ——
     * 那笔钱从佣金池里扣掉却挂不到任何收款方,对账时是个凭空消失的窟窿。
     *
     * <p>改比例**只影响之后发的单**:已发出的单在发单时就把分账定死了。
     */
    void setCommissionRate(String category, String regionCode, int commissionPct,
                            int dispatchRetainPct, Long dispatchOrgId,
                            String reason, long callerUserId);

    record CommissionRateView(String category, String regionCode, int commissionPct,
                               int dispatchRetainPct, Long dispatchOrgId,
                               java.time.Instant updatedAt) { }

    List<CommissionRateView> listCommissionRates(long callerUserId);

    /**
     * @param dispatchOrgId 收留存的派遣公司;没有派遣公司时为 null(留存必然是 0)
     */
    record TotalPriceSplit(long totalPriceCents, long workerCents, long commissionCents,
                            long dispatchRetainCents, long stationPoolCents,
                            Long dispatchOrgId) { }

    /**
     * 设某个服务站在某个类目上的分成比例。
     *
     * <p>{@code stationOrgId} 传 null 表示设**平台默认**(对所有没单独设过的站生效)。
     * 三级取数:站点覆盖 → 平台默认 → 全局兜底参数。
     */
    void setStationRate(Long stationOrgId, String category, int percent, String reason, long callerUserId);

    /** 某站已设的比例(含它继承的平台默认)。 */
    List<StationRateView> listStationRates(Long stationOrgId, long callerUserId);

    // ─────────────── 服务站与用工单位的合作(老系统 M9) ───────────────

    record CooperationView(long id, long stationOrgId, long partnerOrgId, String status,
                           boolean initiatedByStation, java.time.Instant createdAt,
                           java.time.Instant confirmedAt, java.time.Instant endedAt) { }

    record OperatorView(long id, long cooperationId, long userId, boolean active,
                        java.time.Instant createdAt) { }

    /**
     * 发起合作申请。服务站或用工单位任一方都能发起,**对方确认后才生效**。
     *
     * @param initiatedByStation 是否由服务站发起。决定谁能撤回
     */
    long applyCooperation(long stationOrgId, long partnerOrgId,
                          boolean initiatedByStation, long callerUserId);

    /** 确认合作。**只有被申请的那一方能确认** —— 否则两步流程形同虚设。 */
    void confirmCooperation(long cooperationId, long callerUserId);

    /** 撤回未确认的申请。只有发起方能撤。 */
    void cancelCooperation(long cooperationId, long callerUserId);

    /** 解除已生效的合作。任一方都可以 —— 合作是双方的。 */
    void endCooperation(long cooperationId, long callerUserId);

    List<CooperationView> listCooperations(long orgId, long callerUserId);

    /**
     * 指派操作员。**只有服务站站长能派**,而且只能在**已生效**的合作上派 ——
     * 合作还没谈成就先派人,那个人拿着的是一份不存在的授权。
     */
    long assignOperator(long cooperationId, long userId, long callerUserId);

    /** 解绑操作员。**不删记录** —— 他经办过的事要能查到当时是有授权的。 */
    void revokeOperator(long cooperationId, long userId, long callerUserId);

    List<OperatorView> listOperators(long cooperationId, long callerUserId);

    // ─────────────── 服务站间联合(老系统 M10 §3.4) ───────────────

    record StationJointView(long id, long fromOrgId, long toOrgId, int ratePercent,
                            String status, java.time.Instant createdAt,
                            java.time.Instant confirmedAt, java.time.Instant endedAt) { }

    /**
     * 发起联合申请。**只有发起方服务站的法人代表能发** ——
     * 这一步是在决定把自己的佣金分一部分出去。
     *
     * @param ratePercent 从发起方的服务站佣金里切多少给对方(1–99)
     */
    long applyJoint(long fromOrgId, long toOrgId, int ratePercent, long callerUserId);

    /** 确认联合。**只有被邀请方的法人代表能确认** —— 否则发起方可以自己给自己确认。 */
    void confirmJoint(long jointId, long callerUserId);

    /** 撤回未确认的申请(发起方)。 */
    void cancelJoint(long jointId, long callerUserId);

    /** 解除已生效的联合。任一方都可以。 */
    void endJoint(long jointId, long callerUserId);

    /** 这个服务站相关的全部联合(含历史)。 */
    List<StationJointView> listJoints(long orgId, long callerUserId);

    // ─────────────── 服务站与业务员网络 ───────────────

    record StationView(long orgId, String name, long legalRepUserId,
                       Integer stationPercent, int effectivePercent, Instant approvedAt,
                       int brokerCount) { }

    record BrokerNodeView(long userId, Long stationOrgId, Long parentUserId,
                          Instant lastActiveAt, String status, int childCount) { }

    record BrokerChangeView(long id, long brokerUserId, String changeType,
                            String oldValue, String newValue, Long changedBy,
                            Instant changedAt, String reason) { }

    /** 全部服务站。要 PLATFORM_OPS。 */
    List<StationView> listStations(long callerUserId);

    /**
     * 设置服务站佣金比例。**传 null 表示"跟随平台默认"**,
     * 不是"设成 0" —— 这两者含义完全不同(见 station 表的注释)。
     */
    void setStationPercent(long stationOrgId, Integer percent, String reason, long callerUserId);

    /** 某服务站下的业务员;stationOrgId 为 null 时返回全部。 */
    List<BrokerNodeView> listBrokers(Long stationOrgId, long callerUserId);

    /** 把业务员挂到服务站(或传 null 摘除)。留痕。 */
    void assignStation(long brokerUserId, Long stationOrgId, String reason, long callerUserId);

    /**
     * 改业务员的上级(传 null 变成根业务员)。留痕。
     *
     * <p>会拒绝成环:把 A 挂到自己的下级 B 名下,会让 A→B→A 形成闭环,
     * 之后沿链向上分佣金就是死循环。数据库的 CHECK 只挡得住"自己是自己上级"这一层。
     */
    void assignParent(long brokerUserId, Long parentUserId, String reason, long callerUserId);

    /** 业务员活跃打点。降级任务按这个时间判定。 */
    void touchBroker(long brokerUserId);

    /**
     * 手动跑一次降级任务,返回处理人数。要 PLATFORM_OPS。
     *
     * <p>定时任务每天凌晨 3 点自动跑。留一个手动入口是因为:
     * 改完降级天数想立刻看效果、或者服务停过一段时间要补跑,
     * 没有入口就只能等到明天凌晨。
     */
    int runDemotionNow(long callerUserId);

    /** 变更记录;brokerUserId 为 null 时返回最近 100 条。 */
    List<BrokerChangeView> brokerChanges(Long brokerUserId, long callerUserId);
}
