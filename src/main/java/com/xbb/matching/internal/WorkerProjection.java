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

    /** 信用分(§5.4.2 匹配 v1)。null = 还没有信用记录,评分时该维度缺失。 */
    @Column(name = "credit_score")
    private Double creditScore;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /**
     * 写入本行的那个事件的发生时刻。
     *
     * <p>投递是至少一次且**可能乱序**(失败的事件会退避,晚于后来的事件重投)。
     * 没有这个字段时,一条旧的 ProfileUpdated 重投会把新的置信度覆盖回去——
     * 表现是"工人明明履约验证过了,画像却退回自述状态",而且无声无息。
     */
    @Column(name = "source_event_at")
    private Instant sourceEventAt;

    protected WorkerProjection() { }

    public WorkerProjection(long userId, Map<String, Double> tags,
                             Long expectedWageCents, Double lat, Double lon) {
        this.userId = userId;
        update(tags, expectedWageCents, lat, lon);
    }

    /** @return 是否真的写入(旧事件会被忽略) */
    public boolean updateIfNewer(Map<String, Double> tags, Long expectedWageCents,
                                  Double lat, Double lon, Instant eventAt) {
        if (sourceEventAt != null && eventAt != null && eventAt.isBefore(sourceEventAt)) {
            return false;
        }
        update(tags, expectedWageCents, lat, lon);
        this.sourceEventAt = eventAt;
        return true;
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

    /** 信用分单独更新:它来自评价域的 CreditScoreChanged,不跟画像事件同源。 */
    public void updateCreditScore(double creditScore) {
        this.creditScore = creditScore;
        this.updatedAt = Instant.now();
    }

    public Long getUserId() { return userId; }
    public Long getExpectedWageCents() { return expectedWageCents; }
    public Double getLat() { return lat; }
    public Double getLon() { return lon; }
    public Double getCreditScore() { return creditScore; }

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
