package com.xbb.profile.internal;

import jakarta.persistence.*;

/** 岗位归属的只读副本:只记"这个岗位是哪个组织的",用于判断谁有权改它的画像。 */
@Entity
@Table(name = "posted_job_ref", schema = "profile")
public class PostedJobRef {

    @Id
    @Column(name = "job_id")
    private Long jobId;

    @Column(name = "org_id", nullable = false)
    private long orgId;

    protected PostedJobRef() { }

    public PostedJobRef(long jobId, long orgId) {
        this.jobId = jobId;
        this.orgId = orgId;
    }

    public Long getJobId() { return jobId; }
    public long getOrgId() { return orgId; }
}
