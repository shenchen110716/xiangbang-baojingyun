package com.xbb.broker.internal;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 服务站与用工单位的合作(老系统 M9 ComApply)。
 *
 * <p>流程和服务站间联合(V9)刻意做成同一个形状:申请 → 对方确认 → 生效,
 * 可撤回(未确认)、可解除(已生效)。**解除不删行** —— 这段时间里经办过的事
 * 要能解释"当初为什么是他在办"。
 */
@Entity
@Table(name = "station_cooperation", schema = "broker")
public class StationCooperation {

    public enum Status { PENDING, ACTIVE, CANCELLED, ENDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "station_org_id", nullable = false)
    private long stationOrgId;

    @Column(name = "partner_org_id", nullable = false)
    private long partnerOrgId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "initiated_by_station", nullable = false)
    private boolean initiatedByStation;

    @Column(name = "applied_by", nullable = false)
    private long appliedBy;

    @Column(name = "confirmed_by")
    private Long confirmedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Version
    private long version;

    protected StationCooperation() { }

    public StationCooperation(long stationOrgId, long partnerOrgId,
                              boolean initiatedByStation, long appliedBy) {
        if (stationOrgId == partnerOrgId) {
            throw new IllegalArgumentException("不能和自己建立合作");
        }
        this.stationOrgId = stationOrgId;
        this.partnerOrgId = partnerOrgId;
        this.initiatedByStation = initiatedByStation;
        this.appliedBy = appliedBy;
    }

    public void confirm(long confirmerUserId) {
        if (status != Status.PENDING) {
            throw new IllegalStateException("只有待确认的申请可以确认");
        }
        status = Status.ACTIVE;
        confirmedBy = confirmerUserId;
        confirmedAt = Instant.now();
    }

    /** 发起方撤回。只能撤未确认的 —— 已生效的要走解除。 */
    public void cancel() {
        if (status != Status.PENDING) {
            throw new IllegalStateException("只有待确认的申请可以撤回,已生效的请解除合作");
        }
        status = Status.CANCELLED;
        endedAt = Instant.now();
    }

    public void end() {
        if (status != Status.ACTIVE) {
            throw new IllegalStateException("只有已生效的合作可以解除");
        }
        status = Status.ENDED;
        endedAt = Instant.now();
    }

    public Long getId() { return id; }
    public long getStationOrgId() { return stationOrgId; }
    public long getPartnerOrgId() { return partnerOrgId; }
    public Status getStatus() { return status; }
    public boolean isInitiatedByStation() { return initiatedByStation; }
    public long getAppliedBy() { return appliedBy; }
    public Long getConfirmedBy() { return confirmedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public Instant getEndedAt() { return endedAt; }
}
