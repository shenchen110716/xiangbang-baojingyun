package com.xbb.broker.internal;

import jakarta.persistence.*;

import java.time.Instant;

/** 一次分享。员工把岗位或商品分享出去,对方带着分享码进来即归因到这条。 */
@Entity
@Table(name = "share", schema = "broker")
public class Share {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sharer_user_id", nullable = false)
    private long sharerUserId;

    @Column(name = "target_type", nullable = false, length = 32)
    private String targetType;

    @Column(name = "target_id", nullable = false)
    private long targetId;

    @Column(nullable = false, length = 32)
    private String code;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Share() { }

    public Share(long sharerUserId, String targetType, long targetId, String code) {
        this.sharerUserId = sharerUserId;
        this.targetType = targetType;
        this.targetId = targetId;
        this.code = code;
    }

    public Long getId() { return id; }
    public long getSharerUserId() { return sharerUserId; }
    public String getTargetType() { return targetType; }
    public long getTargetId() { return targetId; }
    public String getCode() { return code; }
    public Instant getCreatedAt() { return createdAt; }
}
