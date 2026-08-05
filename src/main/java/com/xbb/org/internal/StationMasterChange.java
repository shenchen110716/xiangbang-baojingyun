package com.xbb.org.internal;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 站长变更留痕(老系统 M10 §4.3「先留痕后变更」)。
 *
 * <p>换站长会改变**谁能设分成比例、谁能签联合协议** —— 那都是动钱的权力,
 * 事后必须查得到是谁在什么时候换的。
 */
@Entity
@Table(name = "station_master_change", schema = "org")
public class StationMasterChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "org_id", nullable = false)
    private long orgId;

    @Column(name = "old_user_id")
    private Long oldUserId;

    @Column(name = "new_user_id")
    private Long newUserId;

    @Column(name = "changed_by", nullable = false)
    private long changedBy;

    @Column(nullable = false, length = 200)
    private String reason;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt = Instant.now();

    protected StationMasterChange() { }

    public StationMasterChange(long orgId, Long oldUserId, Long newUserId, long changedBy, String reason) {
        this.orgId = orgId;
        this.oldUserId = oldUserId;
        this.newUserId = newUserId;
        this.changedBy = changedBy;
        this.reason = reason;
    }

    public Long getId() { return id; }
    public long getOrgId() { return orgId; }
    public Long getOldUserId() { return oldUserId; }
    public Long getNewUserId() { return newUserId; }
    public long getChangedBy() { return changedBy; }
    public String getReason() { return reason; }
    public Instant getChangedAt() { return changedAt; }
}
