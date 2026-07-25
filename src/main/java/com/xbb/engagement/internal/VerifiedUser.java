package com.xbb.engagement.internal;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "verified_user", schema = "engagement")
public class VerifiedUser {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "verified_at", nullable = false)
    private Instant verifiedAt;

    protected VerifiedUser() { }

    public VerifiedUser(long userId, Instant verifiedAt) {
        this.userId = userId;
        this.verifiedAt = verifiedAt;
    }

    public Long getUserId() { return userId; }
    public Instant getVerifiedAt() { return verifiedAt; }
}
