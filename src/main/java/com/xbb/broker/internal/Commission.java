package com.xbb.broker.internal;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "commission", schema = "broker")
public class Commission {

    public static final long RATE_PERCENT = 10;

    public enum Status { PENDING, PAID }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "broker_user_id", nullable = false)
    private long brokerUserId;

    @Column(name = "worker_user_id", nullable = false)
    private long workerUserId;

    @Column(name = "settlement_id", nullable = false, unique = true)
    private long settlementId;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Version
    private long version;

    protected Commission() { }

    public Commission(long brokerUserId, long workerUserId, long settlementId, long amountCents) {
        this.brokerUserId = brokerUserId;
        this.workerUserId = workerUserId;
        this.settlementId = settlementId;
        this.amountCents = amountCents;
    }

    public void pay() {
        if (status != Status.PENDING) throw new IllegalStateException("只有待发放状态可以发放");
        this.status = Status.PAID;
    }

    public Long getId() { return id; }
    public long getBrokerUserId() { return brokerUserId; }
    public long getWorkerUserId() { return workerUserId; }
    public long getSettlementId() { return settlementId; }
    public long getAmountCents() { return amountCents; }
    public Status getStatus() { return status; }
}
