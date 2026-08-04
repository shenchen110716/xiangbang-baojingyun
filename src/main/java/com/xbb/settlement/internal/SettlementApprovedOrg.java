package com.xbb.settlement.internal;

import jakarta.persistence.*;
import java.time.Instant;

/** 组织法人代表的只读副本。类名带域名前缀,理由同 SettlementPostedJob。 */
@Entity
@Table(name = "approved_org", schema = "settlement")
public class SettlementApprovedOrg {

    @Id @Column(name = "org_id")
    private Long orgId;

    @Column(name = "legal_rep_user_id", nullable = false)
    private long legalRepUserId;

    @Column(name = "approved_at", nullable = false)
    private Instant approvedAt;

    protected SettlementApprovedOrg() { }

    public SettlementApprovedOrg(long orgId, long legalRepUserId, Instant approvedAt) {
        this.orgId = orgId;
        this.legalRepUserId = legalRepUserId;
        this.approvedAt = approvedAt;
    }

    public Long getOrgId() { return orgId; }
    public long getLegalRepUserId() { return legalRepUserId; }
    public Instant getApprovedAt() { return approvedAt; }
}
