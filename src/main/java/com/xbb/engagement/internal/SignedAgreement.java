package com.xbb.engagement.internal;

import jakarta.persistence.*;
import java.time.Instant;

/** 已签署协议的本域只读副本——履约完成门禁的判断依据。 */
@Entity
@Table(name = "signed_agreement", schema = "engagement")
public class SignedAgreement {

    @Id
    @Column(name = "application_id")
    private Long applicationId;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "signed_at", nullable = false)
    private Instant signedAt;

    protected SignedAgreement() { }

    public SignedAgreement(long applicationId, String contentHash, Instant signedAt) {
        this.applicationId = applicationId;
        this.contentHash = contentHash;
        this.signedAt = signedAt;
    }

    public Long getApplicationId() { return applicationId; }
    public String getContentHash() { return contentHash; }
    public Instant getSignedAt() { return signedAt; }
}
