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
     * 设某个服务站在某个类目上的分成比例。
     *
     * <p>{@code stationOrgId} 传 null 表示设**平台默认**(对所有没单独设过的站生效)。
     * 三级取数:站点覆盖 → 平台默认 → 全局兜底参数。
     */
    void setStationRate(Long stationOrgId, String category, int percent, String reason, long callerUserId);

    /** 某站已设的比例(含它继承的平台默认)。 */
    List<StationRateView> listStationRates(Long stationOrgId, long callerUserId);

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
