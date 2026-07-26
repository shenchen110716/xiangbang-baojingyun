package com.xbb.fund.internal;

import jakarta.persistence.*;
import java.time.Instant;

/** 信用分的本域只读副本——担保决策要用,不跨域查评价域。 */
@Entity
@Table(name = "worker_credit", schema = "fund")
public class WorkerCredit {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false)
    private double score;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected WorkerCredit() { }

    public WorkerCredit(long userId, double score) {
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
