package com.xbb.settlement.internal;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "settlement", schema = "settlement")
public class Settlement {

    public enum Status { PENDING, PAID, VOIDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false, unique = true)
    private long applicationId;

    @Column(name = "job_id", nullable = false)
    private long jobId;

    @Column(name = "worker_user_id", nullable = false)
    private long workerUserId;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "void_reason")
    private String voidReason;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Version
    private long version;

    protected Settlement() { }

    public Settlement(long applicationId, long jobId, long workerUserId, long amountCents) {
        this.applicationId = applicationId;
        this.jobId = jobId;
        this.workerUserId = workerUserId;
        this.amountCents = amountCents;
    }

    public void pay() {
        if (status != Status.PENDING) throw new IllegalStateException("只有待结算状态可以支付");
        this.status = Status.PAID;
    }

    public void voidSettlement(String reason) {
        if (status != Status.PENDING) throw new IllegalStateException("只有待结算状态可以作废");
        this.status = Status.VOIDED;
        this.voidReason = reason;
    }

    public Long getId() { return id; }
    public long getApplicationId() { return applicationId; }
    public long getJobId() { return jobId; }
    public long getWorkerUserId() { return workerUserId; }
    public long getAmountCents() { return amountCents; }
    public Status getStatus() { return status; }
    public String getVoidReason() { return voidReason; }
}
