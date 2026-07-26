package com.xbb.talent.internal;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Entity
@Table(name = "talent_profile", schema = "talent")
public class TalentProfile {

    @Id
    @Column(name = "user_id")
    private Long userId;

    /** "标签:置信度" 逗号分隔。 */
    @Column(nullable = false)
    private String tags = "";

    @Column(name = "expected_wage_cents")
    private Long expectedWageCents;

    /** 累计完成履约次数——"复用"价值的核心指标:干过的人比没干过的可靠。 */
    @Column(name = "completed_engagements", nullable = false)
    private int completedEngagements = 0;

    @Column(name = "last_active_at")
    private Instant lastActiveAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected TalentProfile() { }

    public TalentProfile(long userId) {
        this.userId = userId;
    }

    public void updateProfile(Map<String, Double> tags, Long expectedWageCents) {
        this.tags = tags.entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue())
                .reduce((a, b) -> a + "," + b).orElse("");
        this.expectedWageCents = expectedWageCents;
        this.updatedAt = Instant.now();
    }

    public void recordEngagementCompleted(Instant at) {
        this.completedEngagements++;
        this.lastActiveAt = at;
        this.updatedAt = Instant.now();
    }

    public Long getUserId() { return userId; }
    public Long getExpectedWageCents() { return expectedWageCents; }
    public int getCompletedEngagements() { return completedEngagements; }
    public Instant getLastActiveAt() { return lastActiveAt; }

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
