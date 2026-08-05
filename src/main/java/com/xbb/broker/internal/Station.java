package com.xbb.broker.internal;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 服务站。身份与入驻审核归组织域,这里是本域副本 + 本域自有的佣金属性。
 *
 * <p>为什么佣金比例存在这里而不是组织域:组织域管的是"这个主体是谁、通没通过审核",
 * 抽几个点是佣金的事。放进组织域会让它开始知道佣金规则,那是另一个域的知识。
 */
@Entity
@Table(name = "station", schema = "broker")
public class Station {

    @Id
    @Column(name = "org_id")
    private Long orgId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "legal_rep_user_id", nullable = false)
    private long legalRepUserId;

    /** 服务站佣金比例(%)。**null = 用平台默认**,见迁移里的说明。 */
    @Column(name = "station_percent")
    private Integer stationPercent;

    @Column(name = "approved_at", nullable = false)
    private Instant approvedAt;

    @Version
    private long version;

    protected Station() { }

    public Station(long orgId, String name, long legalRepUserId, Instant approvedAt) {
        this.orgId = orgId;
        this.name = name;
        this.legalRepUserId = legalRepUserId;
        this.approvedAt = approvedAt;
    }

    public Long getOrgId() { return orgId; }
    public String getName() { return name; }
    public long getLegalRepUserId() { return legalRepUserId; }
    public Integer getStationPercent() { return stationPercent; }
    public Instant getApprovedAt() { return approvedAt; }

    void setStationPercent(Integer percent) {
        if (percent != null && (percent < 0 || percent > 100)) {
            throw new IllegalArgumentException("服务站佣金比例必须在 0 到 100 之间");
        }
        this.stationPercent = percent;
    }

    void rename(String name) { this.name = name; }

    /**
     * 换站长。
     *
     * <p>改动前服务站的负责人是提交人、此后不变,所以副本建好就不用再动。
     * 现在平台可以换站长了 —— **而联合协议的授权依据正是这个副本上的站长**,
     * 不同步的话:换了人之后新站长签不了协议,老站长反而还能签。
     */
    void changeLegalRep(long legalRepUserId) { this.legalRepUserId = legalRepUserId; }
}
