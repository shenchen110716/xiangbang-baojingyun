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

    /** 岗位是否还在招。由 JobClosed 事件落下,报名时据此拦截。 */
    @Column(nullable = false)
    private boolean open = true;

    protected PostedJob() { }

    public PostedJob(long jobId, long orgId, long wageCents) {
        this.jobId = jobId;
        this.orgId = orgId;
        this.wageCents = wageCents;
    }

    public void close() { this.open = false; }

    /** 只更新岗位基础信息,**不碰 open**——重投的 JobPosted 不该让已关闭的岗位复活。 */
    public void updateBasics(long orgId, long wageCents) {
        this.orgId = orgId;
        this.wageCents = wageCents;
    }

    public Long getJobId() { return jobId; }
    public boolean isOpen() { return open; }
    public long getOrgId() { return orgId; }
    public long getWageCents() { return wageCents; }
}
