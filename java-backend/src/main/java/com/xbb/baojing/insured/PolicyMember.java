package com.xbb.baojing.insured;

import java.time.LocalDateTime;

public class PolicyMember {
    private Integer id;
    private Integer policyId;
    private Integer personId;
    private String rateSnapshotJson = "";
    private LocalDateTime effectiveAt;
    private LocalDateTime terminatedAt;
    private String endorsementNo = "";
    private String status = "active";
    private LocalDateTime createdAt;

    // response-only, joined
    private String policyNo;
    private String insurer;
    private String planName;
    private String effectiveMode = "";

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Integer getPolicyId() { return policyId; }
    public void setPolicyId(Integer v) { this.policyId = v; }
    public Integer getPersonId() { return personId; }
    public void setPersonId(Integer v) { this.personId = v; }
    public String getRateSnapshotJson() { return rateSnapshotJson; }
    public void setRateSnapshotJson(String v) { this.rateSnapshotJson = v; }
    public LocalDateTime getEffectiveAt() { return effectiveAt; }
    public void setEffectiveAt(LocalDateTime v) { this.effectiveAt = v; }
    public LocalDateTime getTerminatedAt() { return terminatedAt; }
    public void setTerminatedAt(LocalDateTime v) { this.terminatedAt = v; }
    public String getEndorsementNo() { return endorsementNo; }
    public void setEndorsementNo(String v) { this.endorsementNo = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
    public String getPolicyNo() { return policyNo; }
    public void setPolicyNo(String v) { this.policyNo = v; }
    public String getInsurer() { return insurer; }
    public void setInsurer(String v) { this.insurer = v; }
    public String getPlanName() { return planName; }
    public void setPlanName(String v) { this.planName = v; }
    public String getEffectiveMode() { return effectiveMode; }
    public void setEffectiveMode(String v) { this.effectiveMode = v; }
}
