package com.xbb.baojing.enterprise;

import java.time.LocalDateTime;

public class Enterprise {
    private Integer id;
    private String name;
    private String kind = "企业";
    private String creditCode = "";
    private String contact = "";
    private String phone = "";
    private String status = "pending";
    private Integer agentId;
    private double premiumBalance = 0;
    private double usageBalance = 0;
    private double usageFeeDaily = 0.1;
    private int alertDays = 3;
    private LocalDateTime createdAt;

    // response-only, joined
    private String agentName;
    private Double premiumBalanceTotal;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getKind() { return kind; }
    public void setKind(String v) { this.kind = v; }
    public String getCreditCode() { return creditCode; }
    public void setCreditCode(String v) { this.creditCode = v; }
    public String getContact() { return contact; }
    public void setContact(String v) { this.contact = v; }
    public String getPhone() { return phone; }
    public void setPhone(String v) { this.phone = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public Integer getAgentId() { return agentId; }
    public void setAgentId(Integer v) { this.agentId = v; }
    public double getPremiumBalance() { return premiumBalance; }
    public void setPremiumBalance(double v) { this.premiumBalance = v; }
    public double getUsageBalance() { return usageBalance; }
    public void setUsageBalance(double v) { this.usageBalance = v; }
    public double getUsageFeeDaily() { return usageFeeDaily; }
    public void setUsageFeeDaily(double v) { this.usageFeeDaily = v; }
    public int getAlertDays() { return alertDays; }
    public void setAlertDays(int v) { this.alertDays = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
    public String getAgentName() { return agentName; }
    public void setAgentName(String v) { this.agentName = v; }
    public Double getPremiumBalanceTotal() { return premiumBalanceTotal; }
    public void setPremiumBalanceTotal(Double v) { this.premiumBalanceTotal = v; }
}
