package com.xbb.job.internal;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "approved_org", schema = "job")
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
