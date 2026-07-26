package com.xbb.matching.internal;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 人才画像的本域只读投影。匹配域绝不反写画像域(主文档 §4.2 设计要害:
 * "匹配域不得持有画像与评价的写权限,否则为了算得快直接 join,三域焊死")。
 */
@Entity
@Table(name = "worker_projection", schema = "matching")
public class WorkerProjection {

    @Id
    @Column(name = "user_id")
    private Long userId;

    /** "标签:置信度" 逗号分隔,如 "普工:0.4,叉车:0.4"。 */
    @Column(nullable = false)
    private String tags = "";

    @Column(name = "expected_wage_cents")
    private Long expectedWageCents;

    private Double lat;

    private Double lon;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected WorkerProjection() { }

    public WorkerProjection(long userId, Map<String, Double> tags,
                             Long expectedWageCents, Double lat, Double lon) {
        this.userId = userId;
        update(tags, expectedWageCents, lat, lon);
    }

    public void update(Map<String, Double> tags, Long expectedWageCents, Double lat, Double lon) {
        this.tags = tags.entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue())
                .reduce((a, b) -> a + "," + b).orElse("");
        this.expectedWageCents = expectedWageCents;
        this.lat = lat;
        this.lon = lon;
        this.updatedAt = Instant.now();
    }

    public Long getUserId() { return userId; }
    public Long getExpectedWageCents() { return expectedWageCents; }
    public Double getLat() { return lat; }
    public Double getLon() { return lon; }

    public Map<String, Double> getTags() {
        Map<String, Double> result = new LinkedHashMap<>();
        if (tags.isBlank()) return result;
        for (String entry : tags.split(",")) {
            int sep = entry.lastIndexOf(':');
            result.put(entry.substring(0, sep), Double.parseDouble(entry.substring(sep + 1)));
        }
        return result;
    }
}
