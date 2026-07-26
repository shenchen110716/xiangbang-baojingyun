package com.xbb.reimbursement.internal;

import com.xbb.reimbursement.api.ReimbursementApi;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "reimbursement", schema = "reimbursement")
public class Reimbursement {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "applicant_user_id", nullable = false)
    private long applicantUserId;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Column(nullable = false, length = 300)
    private String reason;

    @Column(name = "invoice_no", length = 100)
    private String invoiceNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReimbursementApi.Status status = ReimbursementApi.Status.SUBMITTED;

    @Column(name = "reject_reason", length = 300)
    private String rejectReason;

    @Column(name = "approver_user_id")
    private Long approverUserId;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Version
    private long version;

    protected Reimbursement() { }

    public Reimbursement(long applicantUserId, long amountCents, String reason, String invoiceNo) {
        if (amountCents <= 0) throw new IllegalArgumentException("报销金额必须为正");
        this.applicantUserId = applicantUserId;
        this.amountCents = amountCents;
        this.reason = reason;
        this.invoiceNo = invoiceNo;
    }

    public void approve(long approverUserId) {
        if (status != ReimbursementApi.Status.SUBMITTED) {
            throw new IllegalStateException("只有待审批的报销可以审批");
        }
        if (approverUserId == applicantUserId) {
            // 自己批自己是内控红线,金额再小也不行
            throw new IllegalStateException("不能审批自己提交的报销");
        }
        this.status = ReimbursementApi.Status.APPROVED;
        this.approverUserId = approverUserId;
    }

    public void reject(long approverUserId, String rejectReason) {
        if (status != ReimbursementApi.Status.SUBMITTED) {
            throw new IllegalStateException("只有待审批的报销可以驳回");
        }
        this.status = ReimbursementApi.Status.REJECTED;
        this.approverUserId = approverUserId;
        this.rejectReason = rejectReason;
    }

    public void resubmit(long amountCents, String reason) {
        if (status != ReimbursementApi.Status.REJECTED) {
            throw new IllegalStateException("只有被驳回的报销可以重新提交");
        }
        if (amountCents <= 0) throw new IllegalArgumentException("报销金额必须为正");
        this.amountCents = amountCents;
        this.reason = reason;
        this.status = ReimbursementApi.Status.SUBMITTED;
        this.rejectReason = null;
    }

    public void markPaid() {
        if (status != ReimbursementApi.Status.APPROVED) {
            throw new IllegalStateException("只有已批准的报销可以打款");
        }
        this.status = ReimbursementApi.Status.PAID;
        this.paidAt = Instant.now();
    }

    public Long getId() { return id; }
    public long getApplicantUserId() { return applicantUserId; }
    public long getAmountCents() { return amountCents; }
    public String getReason() { return reason; }
    public String getInvoiceNo() { return invoiceNo; }
    public ReimbursementApi.Status getStatus() { return status; }
    public String getRejectReason() { return rejectReason; }
}
