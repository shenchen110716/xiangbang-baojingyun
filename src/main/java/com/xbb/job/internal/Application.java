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

    protected Application() { }

    public Application(long jobId, long applicantUserId) {
        this.jobId = jobId;
        this.applicantUserId = applicantUserId;
    }

    public Long getId() { return id; }
    public long getJobId() { return jobId; }
    public long getApplicantUserId() { return applicantUserId; }
    public Status getStatus() { return status; }
}
