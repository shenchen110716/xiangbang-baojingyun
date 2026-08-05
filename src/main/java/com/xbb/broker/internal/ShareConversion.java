package com.xbb.broker.internal;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 一条归因:某人通过某次分享进来了。
 *
 * <p>{@code PENDING} 表示已归因但还没计入升级门槛;{@code COUNTED} 表示已计入。
 * 分开两个状态是因为门槛可能是"成交 N 单" —— 人进来了不等于成交了。
 */
@Entity
@Table(name = "share_conversion", schema = "broker")
public class ShareConversion {

    public enum Status { PENDING, COUNTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "share_id", nullable = false)
    private long shareId;

    @Column(name = "converted_user_id", nullable = false)
    private long convertedUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "reference_id")
    private Long referenceId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "counted_at")
    private Instant countedAt;

    protected ShareConversion() { }

    public ShareConversion(long shareId, long convertedUserId) {
        this.shareId = shareId;
        this.convertedUserId = convertedUserId;
    }

    /**
     * 计入升级门槛。**已计入的不再重复计** —— 中继是至少一次投递,
     * 同一笔成交会重复到达,不挡住的话一单能顶好几单。
     *
     * @return 本次是否真的计入(false 表示之前已经计过)
     */
    public boolean count(long referenceId) {
        if (status == Status.COUNTED) {
            return false;
        }
        this.status = Status.COUNTED;
        this.referenceId = referenceId;
        this.countedAt = Instant.now();
        return true;
    }

    public Long getId() { return id; }
    public long getShareId() { return shareId; }
    public long getConvertedUserId() { return convertedUserId; }
    public Status getStatus() { return status; }
    public Long getReferenceId() { return referenceId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCountedAt() { return countedAt; }
}
