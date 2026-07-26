package com.xbb.review.internal;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "credit_score", schema = "review")
public class CreditScoreRecord {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false)
    private double score;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected CreditScoreRecord() { }

    public CreditScoreRecord(long userId, double score) {
        this.userId = userId;
        this.score = score;
    }

    public void update(double score) {
        this.score = score;
        this.updatedAt = Instant.now();
    }

    public Long getUserId() { return userId; }
    public double getScore() { return score; }
}
