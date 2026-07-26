package com.xbb.profile.internal;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 人才侧的期望薪资与坐标——匹配 v0 的"薪资差"与"距离"两个维度需要,
 * 主文档 §9.2 的 ProfileUpdated 载荷里本来就有 expectedSalaryCents/location。
 */
@Entity
@Table(name = "worker_preference", schema = "profile")
public class WorkerPreference {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "expected_wage_cents", nullable = false)
    private long expectedWageCents;

    @Column(nullable = false)
    private double lat;

    @Column(nullable = false)
    private double lon;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected WorkerPreference() { }

    public WorkerPreference(long userId, long expectedWageCents, double lat, double lon) {
        this.userId = userId;
        update(expectedWageCents, lat, lon);
    }

    public void update(long expectedWageCents, double lat, double lon) {
        this.expectedWageCents = expectedWageCents;
        this.lat = lat;
        this.lon = lon;
        this.updatedAt = Instant.now();
    }

    public Long getUserId() { return userId; }
    public long getExpectedWageCents() { return expectedWageCents; }
    public double getLat() { return lat; }
    public double getLon() { return lon; }
}
