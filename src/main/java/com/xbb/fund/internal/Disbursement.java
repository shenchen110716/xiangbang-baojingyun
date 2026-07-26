package com.xbb.fund.internal;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "disbursement", schema = "fund")
public class Disbursement {

    public enum Status { PENDING, SUCCESS, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payout_id", nullable = false)
    private long payoutId;

    @Column(name = "payee_user_id", nullable = false)
    private long payeeUserId;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    /** 唯一单号(§6.4.2 对账):重发用同一个键,通道侧据此去重,不会重复打钱。 */
    @Column(name = "idempotency_key", nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "external_ref", length = 100)
    private String externalRef;

    /** 完税凭证号(§6.4.2:平台代征代缴,出完税凭证)。 */
    @Column(name = "tax_certificate_no", length = 100)
    private String taxCertificateNo;

    @Column(name = "fail_reason", length = 300)
    private String failReason;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Version
    private long version;

    protected Disbursement() { }

    public Disbursement(long payoutId, long payeeUserId, long amountCents, String idempotencyKey) {
        this.payoutId = payoutId;
        this.payeeUserId = payeeUserId;
        this.amountCents = amountCents;
        this.idempotencyKey = idempotencyKey;
    }

    public void markSuccess(String externalRef, String taxCertificateNo) {
        this.status = Status.SUCCESS;
        this.externalRef = externalRef;
        this.taxCertificateNo = taxCertificateNo;
        this.failReason = null;
    }

    public void markFailed(String reason) {
        this.status = Status.FAILED;
        this.failReason = reason;
    }

    public void recordRetry() {
        if (status == Status.SUCCESS) throw new IllegalStateException("已成功的代发不需要重发");
        this.retryCount++;
    }

    public Long getId() { return id; }
    public long getPayoutId() { return payoutId; }
    public long getPayeeUserId() { return payeeUserId; }
    public long getAmountCents() { return amountCents; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Status getStatus() { return status; }
    public String getExternalRef() { return externalRef; }
    public String getTaxCertificateNo() { return taxCertificateNo; }
    public String getFailReason() { return failReason; }
    public int getRetryCount() { return retryCount; }
}
