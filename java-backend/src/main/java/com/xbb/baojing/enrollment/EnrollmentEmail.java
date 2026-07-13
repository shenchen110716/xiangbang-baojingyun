package com.xbb.baojing.enrollment;

import java.time.LocalDateTime;

public class EnrollmentEmail {
    private Integer id;
    private Integer enterpriseId;
    private Integer planId;
    private String kind;
    private String recipient = "";
    private String filename = "";
    private int peopleCount = 0;
    private String requestId = "";
    private String status = "sent";
    private LocalDateTime createdAt;

    // response-only, joined
    private String enterpriseName;
    private String planName;
    private String insurer;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Integer getEnterpriseId() { return enterpriseId; }
    public void setEnterpriseId(Integer v) { this.enterpriseId = v; }
    public Integer getPlanId() { return planId; }
    public void setPlanId(Integer v) { this.planId = v; }
    public String getKind() { return kind; }
    public void setKind(String v) { this.kind = v; }
    public String getRecipient() { return recipient; }
    public void setRecipient(String v) { this.recipient = v; }
    public String getFilename() { return filename; }
    public void setFilename(String v) { this.filename = v; }
    public int getPeopleCount() { return peopleCount; }
    public void setPeopleCount(int v) { this.peopleCount = v; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String v) { this.requestId = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
    public String getEnterpriseName() { return enterpriseName; }
    public void setEnterpriseName(String v) { this.enterpriseName = v; }
    public String getPlanName() { return planName; }
    public void setPlanName(String v) { this.planName = v; }
    public String getInsurer() { return insurer; }
    public void setInsurer(String v) { this.insurer = v; }
}
