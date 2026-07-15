package com.xbb.baojing.recharge;

import java.time.LocalDateTime;

/** Ports backend/models/finance_accounts.py::RechargeRequest. insurer is
 * what the enterprise picked at submission (display/audit only); accountId
 * is resolved from it and frozen at submission time — confirm/reject must
 * use this stored accountId and never re-resolve, so an admin re-linking an
 * insurer to a different account after submission cannot misdirect an
 * already-submitted request. */
public class RechargeRequest {
    private Integer id;
    private Integer enterpriseId;
    private String accountType; // premium / usage
    private String insurer;
    private Integer accountId;
    private double amount = 0;
    private String receiptFileUrl = "";
    private String status = "pending"; // pending / confirmed / rejected
    private String rejectReason = "";
    private Integer createdBy;
    private Integer confirmedBy;
    private LocalDateTime confirmedAt;
    private LocalDateTime createdAt;

    // response-only, joined
    private String enterpriseName;
    private String receiptDownloadUrl;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Integer getEnterpriseId() { return enterpriseId; }
    public void setEnterpriseId(Integer v) { this.enterpriseId = v; }
    public String getAccountType() { return accountType; }
    public void setAccountType(String v) { this.accountType = v; }
    public String getInsurer() { return insurer; }
    public void setInsurer(String v) { this.insurer = v; }
    public Integer getAccountId() { return accountId; }
    public void setAccountId(Integer v) { this.accountId = v; }
    public double getAmount() { return amount; }
    public void setAmount(double v) { this.amount = v; }
    public String getReceiptFileUrl() { return receiptFileUrl; }
    public void setReceiptFileUrl(String v) { this.receiptFileUrl = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String v) { this.rejectReason = v; }
    public Integer getCreatedBy() { return createdBy; }
    public void setCreatedBy(Integer v) { this.createdBy = v; }
    public Integer getConfirmedBy() { return confirmedBy; }
    public void setConfirmedBy(Integer v) { this.confirmedBy = v; }
    public LocalDateTime getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(LocalDateTime v) { this.confirmedAt = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
    public String getEnterpriseName() { return enterpriseName; }
    public void setEnterpriseName(String v) { this.enterpriseName = v; }
    public String getReceiptDownloadUrl() { return receiptDownloadUrl; }
    public void setReceiptDownloadUrl(String v) { this.receiptDownloadUrl = v; }
}
