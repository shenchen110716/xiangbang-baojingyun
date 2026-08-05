package com.xbb.broker.internal;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 两个服务站之间的联合(老系统 M10 §3.4)。
 *
 * <p>A 站发起并定下比例,B 站确认后生效;之后归集到 A 站的佣金按比例分一份给 B 站。
 *
 * <p><b>方向是有意义的。</b>A→B 和 B→A 是两笔不同的分成,可以同时存在、比例也可以不同 ——
 * 谁发起决定谁从自己的份额里往外切。
 */
@Entity
@Table(name = "station_joint", schema = "broker")
public class StationJoint {

    public enum Status {
        /** 已申请,等对方确认。 */
        PENDING,
        /** 已联合,分成生效。 */
        ACTIVE,
        /** 发起方在对方确认前撤回。 */
        CANCELLED,
        /** 已联合后解除。**不删除** —— 历史佣金要靠它解释当初为什么分给了那个站。 */
        ENDED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "from_org_id", nullable = false)
    private long fromOrgId;

    @Column(name = "to_org_id", nullable = false)
    private long toOrgId;

    @Column(name = "rate_percent", nullable = false)
    private int ratePercent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

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

    protected StationJoint() { }

    public StationJoint(long fromOrgId, long toOrgId, int ratePercent, long appliedBy) {
        if (fromOrgId == toOrgId) {
            throw new IllegalArgumentException("服务站不能和自己联合");
        }
        if (ratePercent <= 0 || ratePercent >= 100) {
            // 0% 没有业务含义;100% 意味着发起方一分不留,多半是填错了
            throw new IllegalArgumentException("联合分成比例必须在 1% 到 99% 之间");
        }
        this.fromOrgId = fromOrgId;
        this.toOrgId = toOrgId;
        this.ratePercent = ratePercent;
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
            throw new IllegalStateException("只有待确认的申请可以撤回,已生效的请解除联合");
        }
        status = Status.CANCELLED;
        endedAt = Instant.now();
    }

    /** 解除已生效的联合。**不删行** —— 历史佣金要靠它解释当初为什么分给了那个站。 */
    public void end() {
        if (status != Status.ACTIVE) {
            throw new IllegalStateException("只有已生效的联合可以解除");
        }
        status = Status.ENDED;
        endedAt = Instant.now();
    }

    public Long getId() { return id; }
    public long getFromOrgId() { return fromOrgId; }
    public long getToOrgId() { return toOrgId; }
    public int getRatePercent() { return ratePercent; }
    public Status getStatus() { return status; }
    public long getAppliedBy() { return appliedBy; }
    public Long getConfirmedBy() { return confirmedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public Instant getEndedAt() { return endedAt; }
}
