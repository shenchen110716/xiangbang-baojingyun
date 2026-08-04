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

    Optional<CommissionView> findCommission(long commissionId);

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
