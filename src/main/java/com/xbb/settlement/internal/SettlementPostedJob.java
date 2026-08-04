package com.xbb.settlement.internal;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 岗位归属的只读副本。类名带域名前缀是因为其它域也有同名概念 ——
 * Spring Data 按类名取 bean 名,不加前缀会撞车(建考勤域时刚栽过)。
 */
@Entity
@Table(name = "posted_job", schema = "settlement")
public class SettlementPostedJob {

    @Id @Column(name = "job_id")
    private Long jobId;

    @Column(name = "org_id", nullable = false)
    private long orgId;

    @Column(name = "posted_at", nullable = false)
    private Instant postedAt;

    protected SettlementPostedJob() { }

    public SettlementPostedJob(long jobId, long orgId, Instant postedAt) {
        this.jobId = jobId;
        this.orgId = orgId;
        this.postedAt = postedAt;
    }

    public Long getJobId() { return jobId; }
    public long getOrgId() { return orgId; }
    public Instant getPostedAt() { return postedAt; }
}
