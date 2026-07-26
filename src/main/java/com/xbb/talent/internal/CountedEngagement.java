package com.xbb.talent.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** 已计入累计次数的履约单。存在即代表"这一单已经数过了",防止重投把次数刷高。 */
@Entity
@Table(name = "counted_engagement", schema = "talent")
public class CountedEngagement {

    @Id
    @Column(name = "application_id")
    private Long applicationId;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(name = "counted_at", nullable = false)
    private Instant countedAt = Instant.now();

    protected CountedEngagement() { }

    public CountedEngagement(long applicationId, long userId) {
        this.applicationId = applicationId;
        this.userId = userId;
    }

    public Long getApplicationId() { return applicationId; }
    public long getUserId() { return userId; }
}
