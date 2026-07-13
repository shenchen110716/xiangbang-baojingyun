package com.xbb.baojing.finance;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class LedgerEntry {
    private Integer id;
    private Integer enterpriseId;
    private String account;
    private String direction;
    private BigDecimal amount;
    private String businessType;
    private String businessId = "";
    private String idempotencyKey = "";
    private Integer createdBy;
    private LocalDateTime occurredAt;

    // response-only, joined
    private String operator;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Integer getEnterpriseId() { return enterpriseId; }
    public void setEnterpriseId(Integer v) { this.enterpriseId = v; }
    public String getAccount() { return account; }
    public void setAccount(String v) { this.account = v; }
    public String getDirection() { return direction; }
    public void setDirection(String v) { this.direction = v; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal v) { this.amount = v; }
    public String getBusinessType() { return businessType; }
    public void setBusinessType(String v) { this.businessType = v; }
    public String getBusinessId() { return businessId; }
    public void setBusinessId(String v) { this.businessId = v; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String v) { this.idempotencyKey = v; }
    public Integer getCreatedBy() { return createdBy; }
    public void setCreatedBy(Integer v) { this.createdBy = v; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime v) { this.occurredAt = v; }
    public String getOperator() { return operator; }
    public void setOperator(String v) { this.operator = v; }
}
