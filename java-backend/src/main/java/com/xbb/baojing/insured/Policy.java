package com.xbb.baojing.insured;

import java.time.LocalDateTime;

public class Policy {
    private Integer id;
    private String policyNo;
    private Integer enterpriseId;
    private Integer planId;
    private double premium = 0;
    private String status = "active";
    private String startDate = "";
    private String endDate = "";
    private LocalDateTime createdAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getPolicyNo() { return policyNo; }
    public void setPolicyNo(String v) { this.policyNo = v; }
    public Integer getEnterpriseId() { return enterpriseId; }
    public void setEnterpriseId(Integer v) { this.enterpriseId = v; }
    public Integer getPlanId() { return planId; }
    public void setPlanId(Integer v) { this.planId = v; }
    public double getPremium() { return premium; }
    public void setPremium(double v) { this.premium = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getStartDate() { return startDate; }
    public void setStartDate(String v) { this.startDate = v; }
    public String getEndDate() { return endDate; }
    public void setEndDate(String v) { this.endDate = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
}
