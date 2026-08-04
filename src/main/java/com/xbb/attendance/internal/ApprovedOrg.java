package com.xbb.attendance.internal;

import jakarta.persistence.*;
import java.time.Instant;

/** 已通过审核的组织(只读副本)。用来回答"谁是这个组织的法人代表"。 */
@Entity
@Table(name = "approved_org", schema = "attendance")
public class ApprovedOrg {

    @Id
    @Column(name = "org_id")
    private Long orgId;

    @Column(name = "legal_rep_user_id", nullable = false)
    private long legalRepUserId;

    @Column(name = "approved_at", nullable = false)
    private Instant approvedAt;

    protected ApprovedOrg() { }

    public ApprovedOrg(long orgId, long legalRepUserId, Instant approvedAt) {
        this.orgId = orgId;
        this.legalRepUserId = legalRepUserId;
        this.approvedAt = approvedAt;
    }

    public Long getOrgId() { return orgId; }
    public long getLegalRepUserId() { return legalRepUserId; }
    public Instant getApprovedAt() { return approvedAt; }
}
