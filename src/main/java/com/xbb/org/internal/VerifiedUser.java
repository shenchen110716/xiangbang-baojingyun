package com.xbb.org.internal;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "verified_user", schema = "org")
public class VerifiedUser {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "real_name", nullable = false)
    private String realName;

    @Column(name = "verified_at", nullable = false)
    private Instant verifiedAt;

    protected VerifiedUser() { }

    public VerifiedUser(Long userId, String realName, Instant verifiedAt) {
        this.userId = userId;
        this.realName = realName;
        this.verifiedAt = verifiedAt;
    }

    public Long getUserId() { return userId; }
    public String getRealName() { return realName; }
    public Instant getVerifiedAt() { return verifiedAt; }
}
