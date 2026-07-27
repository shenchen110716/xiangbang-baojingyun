package com.xbb.matching.internal;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;

/**
 * 岗位画像 + 岗位基本信息的本域只读投影。
 * must/nice 必须分开存:must 进硬约束过滤,nice 进软偏好评分(主文档 §5.2.2)。
 */
@Entity
@Table(name = "job_projection", schema = "matching")
public class JobProjection {

    @Id
    @Column(name = "job_id")
    private Long jobId;

    @Column(name = "org_id", nullable = false)
    private long orgId;

    @Column(name = "wage_cents", nullable = false)
    private long wageCents;

    @Column(name = "must_tags", nullable = false)
    private String mustTags = "";

    @Column(name = "nice_tags", nullable = false)
    private String niceTags = "";

    private Double lat;

    private Double lon;

    /** 招满或被下架后置 false。硬约束"名额未满"落在这一列上(§5.4)。 */
    @Column(nullable = false)
    private boolean open = true;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /**
     * 乐观锁。这一行同样有两个并发写入方(岗位事件与岗位画像事件),
     * 都是"读整行—改一部分—整行写回"。没有版本号时后提交者会盖掉先提交者,
     * 表现为岗位的画像标签或开放状态莫名回退。同 WorkerProjection。
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected JobProjection() { }

    public JobProjection(long jobId, long orgId, long wageCents) {
        this.jobId = jobId;
        this.orgId = orgId;
        this.wageCents = wageCents;
    }

    /** 岗位发布(JobPosted)只带基本信息,不动画像部分。 */
    public void updateBasics(long orgId, long wageCents) {
        this.orgId = orgId;
        this.wageCents = wageCents;
        this.updatedAt = Instant.now();
    }

    /** 岗位画像(JobProfileUpdated)只动标签与坐标,不动薪资。 */
    public void updateProfile(List<String> mustTags, List<String> niceTags, double lat, double lon) {
        this.mustTags = String.join(",", mustTags);
        this.niceTags = String.join(",", niceTags);
        this.lat = lat;
        this.lon = lon;
        this.updatedAt = Instant.now();
    }

    public void close() { this.open = false; }

    public Long getJobId() { return jobId; }
    public boolean isOpen() { return open; }
    public long getOrgId() { return orgId; }
    public long getWageCents() { return wageCents; }
    public List<String> getMustTags() { return split(mustTags); }
    public List<String> getNiceTags() { return split(niceTags); }
    public Double getLat() { return lat; }
    public Double getLon() { return lon; }

    private static List<String> split(String csv) {
        return csv.isBlank() ? List.of() : List.of(csv.split(","));
    }
}
