package com.xbb.broker.internal;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 合作关系下的操作员授权。
 *
 * <p>挂在**合作关系**上而不是服务站上:同一个服务站和三家企业合作,
 * 完全可能派三个不同的人对接;挂在服务站上表达不了这件事,
 * 撤销某一家的授权还会连带影响其它家。
 *
 * <p>解绑只置 {@code active=false},**不删行** ——
 * 这个人经办过的事要能查到当时他是有授权的。
 */
@Entity
@Table(name = "cooperation_operator", schema = "broker")
public class CooperationOperator {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cooperation_id", nullable = false)
    private long cooperationId;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "assigned_by", nullable = false)
    private long assignedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected CooperationOperator() { }

    public CooperationOperator(long cooperationId, long userId, long assignedBy) {
        this.cooperationId = cooperationId;
        this.userId = userId;
        this.assignedBy = assignedBy;
    }

    public void revoke() {
        if (!active) {
            throw new IllegalStateException("这个操作员已经解绑");
        }
        active = false;
        revokedAt = Instant.now();
    }

    public Long getId() { return id; }
    public long getCooperationId() { return cooperationId; }
    public long getUserId() { return userId; }
    public boolean isActive() { return active; }
    public long getAssignedBy() { return assignedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getRevokedAt() { return revokedAt; }
}
