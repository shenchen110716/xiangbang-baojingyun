package com.xbb.baojing.agent;

import java.time.LocalDateTime;

public class AgentCommission {
    private Integer id;
    private Integer agentId;
    private Integer enterpriseId;
    private Integer planId;
    private double rate = 0.15;
    private String mode = "rebate"; // rebate | price | markup
    private double markupAmount = 0;
    private double salePrice = 0;
    private String status = "active";
    private LocalDateTime createdAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Integer getAgentId() { return agentId; }
    public void setAgentId(Integer v) { this.agentId = v; }
    public Integer getEnterpriseId() { return enterpriseId; }
    public void setEnterpriseId(Integer v) { this.enterpriseId = v; }
    public Integer getPlanId() { return planId; }
    public void setPlanId(Integer v) { this.planId = v; }
    public double getRate() { return rate; }
    public void setRate(double v) { this.rate = v; }
    public String getMode() { return mode; }
    public void setMode(String v) { this.mode = v; }
    public double getMarkupAmount() { return markupAmount; }
    public void setMarkupAmount(double v) { this.markupAmount = v; }
    public double getSalePrice() { return salePrice; }
    public void setSalePrice(double v) { this.salePrice = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
}
