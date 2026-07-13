package com.xbb.baojing.plan;

import java.time.LocalDateTime;

public class InsurancePlan {
    private Integer id;
    private String insurer;
    private String insurerEmail = "";
    private String name;
    private String coverage = "";
    private String occupationClasses = "1-4类";
    private double price = 0;
    private double commissionRate = 0;
    private double profitAmount = 0;
    private String paymentMode = "企业直投";
    private String billingMode = "monthly";
    private String effectiveMode = "next_day";
    private String status = "active";
    private LocalDateTime createdAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getInsurer() { return insurer; }
    public void setInsurer(String v) { this.insurer = v; }
    public String getInsurerEmail() { return insurerEmail; }
    public void setInsurerEmail(String v) { this.insurerEmail = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getCoverage() { return coverage; }
    public void setCoverage(String v) { this.coverage = v; }
    public String getOccupationClasses() { return occupationClasses; }
    public void setOccupationClasses(String v) { this.occupationClasses = v; }
    public double getPrice() { return price; }
    public void setPrice(double v) { this.price = v; }
    public double getCommissionRate() { return commissionRate; }
    public void setCommissionRate(double v) { this.commissionRate = v; }
    public double getProfitAmount() { return profitAmount; }
    public void setProfitAmount(double v) { this.profitAmount = v; }
    public String getPaymentMode() { return paymentMode; }
    public void setPaymentMode(String v) { this.paymentMode = v; }
    public String getBillingMode() { return billingMode; }
    public void setBillingMode(String v) { this.billingMode = v; }
    public String getEffectiveMode() { return effectiveMode; }
    public void setEffectiveMode(String v) { this.effectiveMode = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
}
