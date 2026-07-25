package com.xbb.fund.internal;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "payout", schema = "fund")
public class Payout {

    public enum Status { PENDING, PAID, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "settlement_id", nullable = false, unique = true)
    private long settlementId;

    @Column(name = "payee_user_id", nullable = false)
    private long payeeUserId;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Version
    private long version;

    protected Payout() { }

    public Payout(long settlementId, long payeeUserId, long amountCents) {
        this.settlementId = settlementId;
        this.payeeUserId = payeeUserId;
        this.amountCents = amountCents;
    }

    public void disburse() {
        if (status == Status.CANCELLED) throw new IllegalStateException("该结算已作废,不能发放");
        if (status != Status.PENDING) throw new IllegalStateException("只有待发放状态可以发放");
        this.status = Status.PAID;
        this.paidAt = Instant.now();
    }

    public void cancel() {
        if (status != Status.PENDING) return; // 已发放/已作废都不再变更,幂等
        this.status = Status.CANCELLED;
    }

    public Long getId() { return id; }
    public long getSettlementId() { return settlementId; }
    public long getPayeeUserId() { return payeeUserId; }
    public long getAmountCents() { return amountCents; }
    public Status getStatus() { return status; }
    public Instant getPaidAt() { return paidAt; }
}
