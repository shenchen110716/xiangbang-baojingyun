package com.xbb.voice.internal;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "job_session", schema = "voice")
public class VoiceJobSession {

    public enum Status { DRAFT, PUBLISHED, RECALLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "caller_user_id", nullable = false)
    private long callerUserId;

    @Column(name = "org_id", nullable = false)
    private long orgId;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false)
    private int headcount;

    @Column(name = "wage_cents", nullable = false)
    private long wageCents;

    @Column(length = 200)
    private String extra;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.DRAFT;

    @Column(name = "published_job_id")
    private Long publishedJobId;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected VoiceJobSession() { }

    public VoiceJobSession(long callerUserId, long orgId, String title, int headcount,
                            long wageCents, String extra) {
        this.callerUserId = callerUserId;
        this.orgId = orgId;
        this.title = title;
        this.headcount = headcount;
        this.wageCents = wageCents;
        this.extra = extra;
    }

    public void markPublished(long jobId, Instant at) {
        if (status != Status.DRAFT) throw new IllegalStateException("只有草稿可以发布");
        this.status = Status.PUBLISHED;
        this.publishedJobId = jobId;
        this.publishedAt = at;
    }

    public void markRecalled() {
        if (status != Status.PUBLISHED) throw new IllegalStateException("只有已发布的可以撤回");
        this.status = Status.RECALLED;
    }

    public Long getId() { return id; }
    public long getCallerUserId() { return callerUserId; }
    public long getOrgId() { return orgId; }
    public String getTitle() { return title; }
    public int getHeadcount() { return headcount; }
    public long getWageCents() { return wageCents; }
    public String getExtra() { return extra; }
    public Status getStatus() { return status; }
    public Long getPublishedJobId() { return publishedJobId; }
    public Instant getPublishedAt() { return publishedAt; }
}
