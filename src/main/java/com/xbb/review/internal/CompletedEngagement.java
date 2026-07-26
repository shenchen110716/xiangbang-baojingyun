package com.xbb.review.internal;

import jakarta.persistence.*;
import java.time.Instant;

/** 履约完成单的本域只读副本——§5.3 R1"只有完成的履约单可评"的校验依据。 */
@Entity
@Table(name = "completed_engagement", schema = "review")
public class CompletedEngagement {

    @Id
    @Column(name = "application_id")
    private Long applicationId;

    @Column(name = "job_id", nullable = false)
    private long jobId;

    @Column(name = "worker_user_id", nullable = false)
    private long workerUserId;

    @Column(name = "org_id", nullable = false)
    private long orgId;

    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    protected CompletedEngagement() { }

    public CompletedEngagement(long applicationId, long jobId, long workerUserId, long orgId, Instant completedAt) {
        this.applicationId = applicationId;
        this.jobId = jobId;
        this.workerUserId = workerUserId;
        this.orgId = orgId;
        this.completedAt = completedAt;
    }

    public Long getApplicationId() { return applicationId; }
    public long getJobId() { return jobId; }
    public long getWorkerUserId() { return workerUserId; }
    public long getOrgId() { return orgId; }
    public Instant getCompletedAt() { return completedAt; }
}
