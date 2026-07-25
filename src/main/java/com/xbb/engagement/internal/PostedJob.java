package com.xbb.engagement.internal;

import jakarta.persistence.*;

@Entity
@Table(name = "posted_job", schema = "engagement")
public class PostedJob {

    @Id
    @Column(name = "job_id")
    private Long jobId;

    @Column(name = "org_id", nullable = false)
    private long orgId;

    @Column(name = "wage_cents", nullable = false)
    private long wageCents;

    protected PostedJob() { }

    public PostedJob(long jobId, long orgId, long wageCents) {
        this.jobId = jobId;
        this.orgId = orgId;
        this.wageCents = wageCents;
    }

    public Long getJobId() { return jobId; }
    public long getOrgId() { return orgId; }
    public long getWageCents() { return wageCents; }
}
