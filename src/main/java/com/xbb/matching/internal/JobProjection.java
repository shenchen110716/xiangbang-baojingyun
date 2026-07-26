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

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

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

    public Long getJobId() { return jobId; }
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
