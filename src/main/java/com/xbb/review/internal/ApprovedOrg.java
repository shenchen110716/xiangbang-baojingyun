package com.xbb.review.internal;

import jakarta.persistence.*;
import java.time.Instant;

/** 组织的只读副本,只为回答"谁是这个组织的法人代表"。订阅 OrganizationApproved 落地。 */
@Entity
@Table(name = "approved_org", schema = "review")
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
}
