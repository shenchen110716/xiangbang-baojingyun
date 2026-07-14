package com.xbb.baojing.position;

import java.time.LocalDateTime;

public class WorkPosition {
    private Integer id;
    private Integer enterpriseId;
    private Integer actualEmployerId;
    private String actualEmployer = "";
    private String name;
    private String occupationClass = "待定";
    private Integer planId;
    private String status = "pending";
    private Integer createdBy;
    private LocalDateTime createdAt;

    // response-only, joined
    private String actualEmployerName;
    private String planName;
    private String creatorName;
    private int videoCount;
    private String latestVideoStatus;
    private String reviewNote;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Integer getEnterpriseId() { return enterpriseId; }
    public void setEnterpriseId(Integer v) { this.enterpriseId = v; }
    public Integer getActualEmployerId() { return actualEmployerId; }
    public void setActualEmployerId(Integer v) { this.actualEmployerId = v; }
    public String getActualEmployer() { return actualEmployer; }
    public void setActualEmployer(String v) { this.actualEmployer = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getOccupationClass() { return occupationClass; }
    public void setOccupationClass(String v) { this.occupationClass = v; }
    public Integer getPlanId() { return planId; }
    public void setPlanId(Integer v) { this.planId = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public Integer getCreatedBy() { return createdBy; }
    public void setCreatedBy(Integer v) { this.createdBy = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
    public String getActualEmployerName() { return actualEmployerName; }
    public void setActualEmployerName(String v) { this.actualEmployerName = v; }
    public String getPlanName() { return planName; }
    public void setPlanName(String v) { this.planName = v; }
    public String getCreatorName() { return creatorName; }
    public void setCreatorName(String v) { this.creatorName = v; }
    public int getVideoCount() { return videoCount; }
    public void setVideoCount(int v) { this.videoCount = v; }
    public String getLatestVideoStatus() { return latestVideoStatus; }
    public void setLatestVideoStatus(String v) { this.latestVideoStatus = v; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String v) { this.reviewNote = v; }
}
