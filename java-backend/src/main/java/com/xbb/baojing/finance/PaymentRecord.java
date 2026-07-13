package com.xbb.baojing.finance;

import java.time.LocalDateTime;

public class PaymentRecord {
    private Integer id;
    private String orderNo;
    private Integer enterpriseId;
    private String account = "premium";
    private double amount = 0;
    private String status = "pending";
    private String provider = "payment";
    private LocalDateTime createdAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String v) { this.orderNo = v; }
    public Integer getEnterpriseId() { return enterpriseId; }
    public void setEnterpriseId(Integer v) { this.enterpriseId = v; }
    public String getAccount() { return account; }
    public void setAccount(String v) { this.account = v; }
    public double getAmount() { return amount; }
    public void setAmount(double v) { this.amount = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getProvider() { return provider; }
    public void setProvider(String v) { this.provider = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
}
