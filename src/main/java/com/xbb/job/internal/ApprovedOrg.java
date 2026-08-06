package com.xbb.job.internal;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "approved_org", schema = "job")
public class ApprovedOrg {

    @Id
    @Column(name = "org_id")
    private Long orgId;

    @Column(name = "legal_rep_user_id", nullable = false)
    private long legalRepUserId;

    @Column(name = "approved_at", nullable = false)
    private Instant approvedAt;

    /**
     * 单位名称与地址。**都可空** —— 这两个字段是 2026-08-06 才加进事件的,
     * 重放那之前落库的 outbox 载荷时 Jackson 给 null,
     * 副本不容忍的话一次重放就把整条中继卡死。
     */
    @Column(length = 100)
    private String name;

    @Column(length = 200)
    private String address;

    protected ApprovedOrg() { }

    public ApprovedOrg(long orgId, long legalRepUserId, Instant approvedAt) {
        this(orgId, legalRepUserId, approvedAt, null, null);
    }

    public ApprovedOrg(long orgId, long legalRepUserId, Instant approvedAt,
                        String name, String address) {
        this.orgId = orgId;
        this.legalRepUserId = legalRepUserId;
        this.approvedAt = approvedAt;
        this.name = name;
        this.address = address;
    }

    /**
     * 用新事件刷新副本。**收到 null 时保留原值,不覆盖** ——
     * 重放旧载荷、或某条只关心站长变更的事件没带名称时,
     * 覆盖下去会把单位名抹成空白,而求职端的岗位卡片全靠它
     */
    public void refresh(long legalRepUserId, Instant approvedAt, String name, String address) {
        this.legalRepUserId = legalRepUserId;
        this.approvedAt = approvedAt;
        if (name != null) this.name = name;
        if (address != null) this.address = address;
    }

    public Long getOrgId() { return orgId; }
    public long getLegalRepUserId() { return legalRepUserId; }
    public Instant getApprovedAt() { return approvedAt; }
    /** @return 可能为 null(旧载荷重放) */
    public String getName() { return name; }
    /** @return 可能为 null */
    public String getAddress() { return address; }
}
