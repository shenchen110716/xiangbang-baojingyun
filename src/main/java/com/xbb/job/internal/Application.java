package com.xbb.job.internal;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "application", schema = "job")
public class Application {

    public enum Status { PENDING, ACCEPTED, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private long jobId;

    @Column(name = "applicant_user_id", nullable = false)
    private long applicantUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    // 乐观锁:同 Organization 的 TOCTOU 修复,防止并发 accept/reject 都成功
    @Version
    private long version;

    protected Application() { }

    public Application(long jobId, long applicantUserId) {
        this.jobId = jobId;
        this.applicantUserId = applicantUserId;
    }

    public void accept() {
        if (status != Status.PENDING) throw new IllegalStateException("只有待处理状态可以处理");
        this.status = Status.ACCEPTED;
    }

    public void reject() {
        if (status != Status.PENDING) throw new IllegalStateException("只有待处理状态可以处理");
        this.status = Status.REJECTED;
    }

    public Long getId() { return id; }
    public long getJobId() { return jobId; }
    public long getApplicantUserId() { return applicantUserId; }
    public Status getStatus() { return status; }
}
