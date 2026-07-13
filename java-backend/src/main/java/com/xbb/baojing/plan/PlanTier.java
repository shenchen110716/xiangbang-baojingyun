package com.xbb.baojing.plan;

import java.time.LocalDateTime;

public class PlanTier {
    private Integer id;
    private Integer planId;
    private String occupationClass;
    private double price = 0;
    private String coverage = "";
    private String status = "active";
    private LocalDateTime createdAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Integer getPlanId() { return planId; }
    public void setPlanId(Integer v) { this.planId = v; }
    public String getOccupationClass() { return occupationClass; }
    public void setOccupationClass(String v) { this.occupationClass = v; }
    public double getPrice() { return price; }
    public void setPrice(double v) { this.price = v; }
    public String getCoverage() { return coverage; }
    public void setCoverage(String v) { this.coverage = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
}
