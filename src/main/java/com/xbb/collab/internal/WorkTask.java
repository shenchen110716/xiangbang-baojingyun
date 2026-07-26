package com.xbb.collab.internal;

import com.xbb.collab.api.CollabApi;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "work_task", schema = "collab")
public class WorkTask {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    private String detail;

    @Column(name = "creator_user_id", nullable = false)
    private long creatorUserId;

    @Column(name = "assignee_user_id", nullable = false)
    private long assigneeUserId;

    @Column(name = "related_job_id")
    private Long relatedJobId;

    @Column(name = "related_org_id")
    private Long relatedOrgId;

    @Column(nullable = false)
    private int progress = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CollabApi.TaskStatus status = CollabApi.TaskStatus.OPEN;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "closed_at")
    private Instant closedAt;

    @Version
    private long version;

    protected WorkTask() { }

    public WorkTask(String title, String detail, long creatorUserId, long assigneeUserId,
                     Long relatedJobId, Long relatedOrgId) {
        this.title = title;
        this.detail = detail;
        this.creatorUserId = creatorUserId;
        this.assigneeUserId = assigneeUserId;
        this.relatedJobId = relatedJobId;
        this.relatedOrgId = relatedOrgId;
    }

    public void updateProgress(int progress) {
        if (status == CollabApi.TaskStatus.CLOSED) throw new IllegalStateException("已关闭的任务不能再更新");
        if (progress < 0 || progress > 100) throw new IllegalArgumentException("进度必须在 0 到 100 之间");
        this.progress = progress;
    }

    public void close() {
        if (status == CollabApi.TaskStatus.CLOSED) throw new IllegalStateException("任务已关闭");
        this.status = CollabApi.TaskStatus.CLOSED;
        this.closedAt = Instant.now();
    }

    public boolean canBeManagedBy(long userId) {
        return userId == creatorUserId || userId == assigneeUserId;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getDetail() { return detail; }
    public long getCreatorUserId() { return creatorUserId; }
    public long getAssigneeUserId() { return assigneeUserId; }
    public Long getRelatedJobId() { return relatedJobId; }
    public Long getRelatedOrgId() { return relatedOrgId; }
    public int getProgress() { return progress; }
    public CollabApi.TaskStatus getStatus() { return status; }
}
