package com.xbb.attendance.internal;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 履约单的只读副本。铁律 3:需要他域数据时订阅事件、在本域维护副本,不跨域查库
 * (铁律 1 也会在数据库层挡住)。
 *
 * <p>录考勤前要能回答"这个人这天确实为这个岗位在岗吗" —— 没有副本就只能跨域查。
 */
@Entity
@Table(name = "engaged_worker", schema = "attendance")
public class EngagedWorker {

    @Id
    @Column(name = "application_id")
    private Long applicationId;

    @Column(name = "job_id", nullable = false)
    private long jobId;

    @Column(name = "worker_user_id", nullable = false)
    private long workerUserId;

    @Column(name = "org_id", nullable = false)
    private long orgId;

    @Column(name = "accepted_at", nullable = false)
    private Instant acceptedAt;

    protected EngagedWorker() { }

    public EngagedWorker(long applicationId, long jobId, long workerUserId, long orgId, Instant acceptedAt) {
        this.applicationId = applicationId;
        this.jobId = jobId;
        this.workerUserId = workerUserId;
        this.orgId = orgId;
        this.acceptedAt = acceptedAt;
    }

    public Long getApplicationId() { return applicationId; }
    public long getJobId() { return jobId; }
    public long getWorkerUserId() { return workerUserId; }
    public long getOrgId() { return orgId; }
    public Instant getAcceptedAt() { return acceptedAt; }
}
