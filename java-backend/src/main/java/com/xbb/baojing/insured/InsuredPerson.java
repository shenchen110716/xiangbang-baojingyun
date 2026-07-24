package com.xbb.baojing.insured;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.xbb.baojing.plan.PricingSnapshot;

import java.time.LocalDateTime;

public class InsuredPerson {
    private Integer id;
    private Integer enterpriseId;
    private String name;
    private String phone = "";
    private String idNumber = "";
    private String occupation = "";
    private String occupationClass = "3类";
    private Integer positionId;
    private String status = "pending";
    private Integer policyId;
    // 保司标注的异常原因/时间/操作人（2026-07-24 insurer-portal 员工参停保
    // 异常标注功能，PATCH /insured/{id}/insurer-flag）。空字符串表示未标注。
    // 只写这三个字段，参保状态本身不受影响——见 backend/models/insured.py。
    private String insurerFlagReason = "";
    private LocalDateTime insurerFlaggedAt;
    private Integer insurerFlaggedBy;
    private LocalDateTime createdAt;

    // response-only, joined
    private String enterpriseName;
    private String positionName;
    private String actualEmployerName;
    private Integer planId;
    private String planName;
    private String insurer;
    private String policyNo;
    private String policyStatus;
    private String effectiveMode = "";
    private String billingMode = "";
    // 生效时间/停保时间 — from the person's most recent PolicyMember row
    // (backend/services/policy_members.py's activate/terminate bridge),
    // not columns on insured_people itself.
    private LocalDateTime effectiveAt;
    private LocalDateTime terminatedAt;
    private PricingSnapshot pricing;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Integer getEnterpriseId() { return enterpriseId; }
    public void setEnterpriseId(Integer v) { this.enterpriseId = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getPhone() { return phone; }
    public void setPhone(String v) { this.phone = v; }
    public String getIdNumber() { return idNumber; }
    public void setIdNumber(String v) { this.idNumber = v; }
    public String getOccupation() { return occupation; }
    public void setOccupation(String v) { this.occupation = v; }
    public String getOccupationClass() { return occupationClass; }
    public void setOccupationClass(String v) { this.occupationClass = v; }
    public Integer getPositionId() { return positionId; }
    public void setPositionId(Integer v) { this.positionId = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public Integer getPolicyId() { return policyId; }
    public void setPolicyId(Integer v) { this.policyId = v; }
    public String getInsurerFlagReason() { return insurerFlagReason; }
    public void setInsurerFlagReason(String v) { this.insurerFlagReason = v; }
    public LocalDateTime getInsurerFlaggedAt() { return insurerFlaggedAt; }
    public void setInsurerFlaggedAt(LocalDateTime v) { this.insurerFlaggedAt = v; }
    public Integer getInsurerFlaggedBy() { return insurerFlaggedBy; }
    public void setInsurerFlaggedBy(Integer v) { this.insurerFlaggedBy = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
    public String getEnterpriseName() { return enterpriseName; }
    public void setEnterpriseName(String v) { this.enterpriseName = v; }
    public String getPositionName() { return positionName; }
    public void setPositionName(String v) { this.positionName = v; }
    public String getActualEmployerName() { return actualEmployerName; }
    public void setActualEmployerName(String v) { this.actualEmployerName = v; }
    public Integer getPlanId() { return planId; }
    public void setPlanId(Integer v) { this.planId = v; }
    public String getPlanName() { return planName; }
    public void setPlanName(String v) { this.planName = v; }
    public String getInsurer() { return insurer; }
    public void setInsurer(String v) { this.insurer = v; }
    public String getPolicyNo() { return policyNo; }
    public void setPolicyNo(String v) { this.policyNo = v; }
    public String getPolicyStatus() { return policyStatus; }
    public void setPolicyStatus(String v) { this.policyStatus = v; }
    public String getEffectiveMode() { return effectiveMode; }
    public void setEffectiveMode(String v) { this.effectiveMode = v; }
    public String getBillingMode() { return billingMode; }
    public void setBillingMode(String v) { this.billingMode = v; }
    public LocalDateTime getEffectiveAt() { return effectiveAt; }
    public void setEffectiveAt(LocalDateTime v) { this.effectiveAt = v; }
    public LocalDateTime getTerminatedAt() { return terminatedAt; }
    public void setTerminatedAt(LocalDateTime v) { this.terminatedAt = v; }
    // @JsonUnwrapped inlines PricingSnapshot's fields directly into this
    // object's JSON — the frontend type is `InsuredPerson extends
    // Partial<PricingSnapshot>` (flat), matching Python's dict-spread
    // `{**serialize(x), **pricing_snapshot(...)}` exactly. Only set when
    // the person's position has a bound plan (mirrors the Python
    // conditional spread), otherwise omitted (null) as if absent.
    @JsonUnwrapped
    public PricingSnapshot getPricing() { return pricing; }
    public void setPricing(PricingSnapshot v) { this.pricing = v; }
}
