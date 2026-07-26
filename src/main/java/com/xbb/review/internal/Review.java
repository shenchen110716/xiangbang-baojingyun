package com.xbb.review.internal;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "review", schema = "review")
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false)
    private long applicationId;

    @Column(name = "rater_user_id", nullable = false)
    private long raterUserId;

    /** 工厂评工人时是被评工人;工人评工厂时为 null。 */
    @Column(name = "ratee_user_id")
    private Long rateeUserId;

    /** 工人评工厂时是被评组织;工厂评工人时为 null。 */
    @Column(name = "ratee_org_id")
    private Long rateeOrgId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReviewTag.Direction direction;

    @Column(nullable = false)
    private String tags = "";

    @Column(name = "comment")
    private String comment;

    @Column(nullable = false)
    private double score;

    /** 双盲(§5.3 R2):双方都提交、或首评满 7 天,才公开。 */
    @Column(nullable = false)
    private boolean visible = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Review() { }

    public Review(long applicationId, long raterUserId, Long rateeUserId, Long rateeOrgId,
                   ReviewTag.Direction direction, List<String> tags, String comment, double score) {
        this.applicationId = applicationId;
        this.raterUserId = raterUserId;
        this.rateeUserId = rateeUserId;
        this.rateeOrgId = rateeOrgId;
        this.direction = direction;
        this.tags = String.join(",", tags);
        this.comment = comment;
        this.score = score;
    }

    public void reveal() {
        this.visible = true;
    }

    public Long getId() { return id; }
    public long getApplicationId() { return applicationId; }
    public long getRaterUserId() { return raterUserId; }
    public Long getRateeUserId() { return rateeUserId; }
    public Long getRateeOrgId() { return rateeOrgId; }
    public ReviewTag.Direction getDirection() { return direction; }
    public List<String> getTags() { return tags.isBlank() ? List.of() : List.of(tags.split(",")); }
    public String getComment() { return comment; }
    public double getScore() { return score; }
    public boolean isVisible() { return visible; }
    public Instant getCreatedAt() { return createdAt; }
}
