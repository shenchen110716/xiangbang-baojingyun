package com.xbb.baojing.finance;

import java.time.LocalDateTime;

public class Invoice {
    private Integer id;
    private Integer enterpriseId;
    private String account = "premium";
    private double amount = 0;
    private String title = "";
    private String taxNo = "";
    private String email = "";
    private String status = "pending";
    private LocalDateTime createdAt;

    // response-only, joined
    private String enterpriseName;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Integer getEnterpriseId() { return enterpriseId; }
    public void setEnterpriseId(Integer v) { this.enterpriseId = v; }
    public String getAccount() { return account; }
    public void setAccount(String v) { this.account = v; }
    public double getAmount() { return amount; }
    public void setAmount(double v) { this.amount = v; }
    public String getTitle() { return title; }
    public void setTitle(String v) { this.title = v; }
    public String getTaxNo() { return taxNo; }
    public void setTaxNo(String v) { this.taxNo = v; }
    public String getEmail() { return email; }
    public void setEmail(String v) { this.email = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
    public String getEnterpriseName() { return enterpriseName; }
    public void setEnterpriseName(String v) { this.enterpriseName = v; }
}
