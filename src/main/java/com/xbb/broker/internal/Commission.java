package com.xbb.broker.internal;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 一份佣金分账。同一笔结算会生成**多条**:主动、被动(逐级)、服务站。
 *
 * <p>收款方是人({@code brokerUserId})或站({@code stationOrgId}),**恰好其一** ——
 * 数据库有 CHECK 兜底。少了这条约束,一条既没有人也没有站的佣金会静静躺在表里,
 * 永远没人来领,而且对账时看不出来。
 *
 * <p>平台那一份不进这张表:它记入资金域的平台收入账户,那里才是账本。
 */
@Entity
@Table(name = "commission", schema = "broker")
public class Commission {

    /**
     * @deprecated 单一 10% 的年代留下的。现在按 {@link CommissionSplitter} 六档分,
     *             比例来自参数中心。留着只为让既有测试还能编译,新代码不要用。
     */
    @Deprecated
    public static final long RATE_PERCENT = 10;

    public enum Status { PENDING, PAID }

    /** 与 {@link CommissionSplitter.Tier} 一一对应。 */
    public enum Tier { ACTIVE, PASSIVE, STATION }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 收款人。服务站分账时为 null。 */
    @Column(name = "broker_user_id")
    private Long brokerUserId;

    /** 收款服务站。仅 STATION 档有值。 */
    @Column(name = "station_org_id")
    private Long stationOrgId;

    @Column(name = "worker_user_id", nullable = false)
    private long workerUserId;

    @Column(name = "settlement_id", nullable = false)
    private long settlementId;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Tier tier = Tier.ACTIVE;

    /** 被动分账在链上的层级(1 = 直接经纪人的上级)。主动与服务站为 0。 */
    @Column(name = "chain_depth", nullable = false)
    private int chainDepth;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Version
    private long version;

    protected Commission() { }

    /** 给人的分账(主动 / 被动)。 */
    static Commission toBroker(long brokerUserId, long workerUserId, long settlementId,
                               long amountCents, Tier tier, int chainDepth) {
        Commission c = new Commission();
        c.brokerUserId = brokerUserId;
        c.workerUserId = workerUserId;
        c.settlementId = settlementId;
        c.amountCents = amountCents;
        c.tier = tier;
        c.chainDepth = chainDepth;
        return c;
    }

    /** 给服务站的分账。 */
    static Commission toStation(long stationOrgId, long workerUserId, long settlementId, long amountCents) {
        Commission c = new Commission();
        c.stationOrgId = stationOrgId;
        c.workerUserId = workerUserId;
        c.settlementId = settlementId;
        c.amountCents = amountCents;
        c.tier = Tier.STATION;
        return c;
    }

    /** 老构造器。保留是为了不动既有测试,新代码走 {@link #toBroker}。 */
    @Deprecated
    public Commission(long brokerUserId, long workerUserId, long settlementId, long amountCents) {
        this.brokerUserId = brokerUserId;
        this.workerUserId = workerUserId;
        this.settlementId = settlementId;
        this.amountCents = amountCents;
    }

    public void pay() {
        if (status != Status.PENDING) throw new IllegalStateException("只有待发放状态可以发放");
        this.status = Status.PAID;
    }

    public Long getId() { return id; }
    public Long getBrokerUserId() { return brokerUserId; }
    public Long getStationOrgId() { return stationOrgId; }
    public long getWorkerUserId() { return workerUserId; }
    public long getSettlementId() { return settlementId; }
    public long getAmountCents() { return amountCents; }
    public Tier getTier() { return tier; }
    public int getChainDepth() { return chainDepth; }
    public Status getStatus() { return status; }
}
