package com.xbb.profile.internal;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "profile_tag", schema = "profile")
public class ProfileTag {

    /**
     * 三层置信(主文档 §5.2):自述 0.4 < 平台验证 0.7 < 履约验证 1.0。
     * "他说他会,不算数;他干过并且评价好,才算数。"
     * 平台验证(证书/技能测试)还没有数据源,先不放进来。
     */
    public enum Source {
        SELF_REPORTED(0.4),
        ENGAGEMENT_VERIFIED(1.0);

        private final double confidence;

        Source(double confidence) { this.confidence = confidence; }

        public double confidence() { return confidence; }
    }


    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(name = "tag_name", nullable = false, length = 50)
    private String tagName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Source source = Source.SELF_REPORTED;

    @Column(nullable = false)
    private double confidence = 0.4;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected ProfileTag() { }

    public ProfileTag(long userId, String tagName) {
        this.userId = userId;
        this.tagName = tagName;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    /**
     * 履约反哺(§5.2"这个模块的灵魂"):干过该岗位 → 自述升级为履约验证,置信 0.4 → 1.0。
     * 已经是履约验证的不降级。
     */
    public void markEngagementVerified() {
        if (this.source == Source.ENGAGEMENT_VERIFIED) return;
        this.source = Source.ENGAGEMENT_VERIFIED;
        this.confidence = Source.ENGAGEMENT_VERIFIED.confidence();
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public long getUserId() { return userId; }
    public String getTagName() { return tagName; }
    public Source getSource() { return source; }
    public double getConfidence() { return confidence; }
    public Instant getUpdatedAt() { return updatedAt; }
}
