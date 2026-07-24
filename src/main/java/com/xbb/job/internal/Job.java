package com.xbb.job.internal;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "job", schema = "job")
public class Job {

    public enum Status { OPEN, CLOSED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "org_id", nullable = false)
    private long orgId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String description;

    @Column(name = "wage_cents", nullable = false)
    private long wageCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.OPEN;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected Job() { }

    public Job(long orgId, String title, String description, long wageCents) {
        this.orgId = orgId;
        this.title = title;
        this.description = description;
        this.wageCents = wageCents;
    }

    public Long getId() { return id; }
    public long getOrgId() { return orgId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public long getWageCents() { return wageCents; }
    public Status getStatus() { return status; }
}
