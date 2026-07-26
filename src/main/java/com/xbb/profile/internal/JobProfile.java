package com.xbb.profile.internal;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;

/**
 * 岗位画像(主文档 §5.2.2"岗位画像必须与人才画像对称")。
 * must/nice 分层是关键设计:must 进硬约束过滤,nice 进软偏好评分——
 * "把'必须持叉车证'当加分项,就会把没证的人推给叉车岗,他去了也上不了车"。
 */
@Entity
@Table(name = "job_profile", schema = "profile")
public class JobProfile {

    @Id
    @Column(name = "job_id")
    private Long jobId;

    // 标签数量小(受控词表 16 个词)、只整体读写不做单标签查询,逗号分隔存储,
    // 建关联表是过度设计。真要做"按标签检索岗位"再拆。
    @Column(name = "must_tags", nullable = false)
    private String mustTags = "";

    @Column(name = "nice_tags", nullable = false)
    private String niceTags = "";

    @Column(nullable = false)
    private double lat;

    @Column(nullable = false)
    private double lon;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected JobProfile() { }

    public JobProfile(long jobId, List<String> mustTags, List<String> niceTags, double lat, double lon) {
        this.jobId = jobId;
        update(mustTags, niceTags, lat, lon);
    }

    public void update(List<String> mustTags, List<String> niceTags, double lat, double lon) {
        this.mustTags = String.join(",", mustTags);
        this.niceTags = String.join(",", niceTags);
        this.lat = lat;
        this.lon = lon;
        this.updatedAt = Instant.now();
    }

    public Long getJobId() { return jobId; }
    public List<String> getMustTags() { return split(mustTags); }
    public List<String> getNiceTags() { return split(niceTags); }
    public double getLat() { return lat; }
    public double getLon() { return lon; }

    private static List<String> split(String csv) {
        return csv.isBlank() ? List.of() : List.of(csv.split(","));
    }
}
