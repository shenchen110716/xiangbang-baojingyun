package com.xbb.baojing.claim;

import java.time.LocalDateTime;
import java.util.List;

public class Claim {
    private Integer id;
    private Integer enterpriseId;
    private Integer personId;
    private String claimNo;
    private String description = "";
    private String status = "reported";
    private double amount = 0;
    private String accidentAt = "";
    private String accidentPlace = "";
    private String accidentType = "工伤事故";
    private String hospital = "";
    private String diagnosis = "";
    private double medicalCost = 0;
    private String contactName = "";
    private String contactPhone = "";
    private String insurerReportNo = "";
    private String currentHandler = "平台理赔专员";
    private String deadline = "";
    private double approvedAmount = 0;
    private String paidAt = "";
    private String rejectionReason = "";
    private String reviewNote = "";
    private String slaDeadline = "";
    private String riskLevel = "normal";
    private LocalDateTime createdAt;

    // response-only, joined/computed — claim_payload() in services/claims.py
    private String enterpriseName;
    private String personName;
    private String idNumber;
    private String positionName;
    private String actualEmployerName;
    private String policyNo;
    private String planName;
    private String insurer;
    private int documentCount;
    private int missingCount;
    private List<String> missingTypes;
    private int completePercent;
    private Integer deadlineDays;
    private boolean deadlineOverdue;
    private boolean slaOverdue;
    private String calculatedRisk;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Integer getEnterpriseId() { return enterpriseId; }
    public void setEnterpriseId(Integer v) { this.enterpriseId = v; }
    public Integer getPersonId() { return personId; }
    public void setPersonId(Integer v) { this.personId = v; }
    public String getClaimNo() { return claimNo; }
    public void setClaimNo(String v) { this.claimNo = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public double getAmount() { return amount; }
    public void setAmount(double v) { this.amount = v; }
    public String getAccidentAt() { return accidentAt; }
    public void setAccidentAt(String v) { this.accidentAt = v; }
    public String getAccidentPlace() { return accidentPlace; }
    public void setAccidentPlace(String v) { this.accidentPlace = v; }
    public String getAccidentType() { return accidentType; }
    public void setAccidentType(String v) { this.accidentType = v; }
    public String getHospital() { return hospital; }
    public void setHospital(String v) { this.hospital = v; }
    public String getDiagnosis() { return diagnosis; }
    public void setDiagnosis(String v) { this.diagnosis = v; }
    public double getMedicalCost() { return medicalCost; }
    public void setMedicalCost(double v) { this.medicalCost = v; }
    public String getContactName() { return contactName; }
    public void setContactName(String v) { this.contactName = v; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String v) { this.contactPhone = v; }
    public String getInsurerReportNo() { return insurerReportNo; }
    public void setInsurerReportNo(String v) { this.insurerReportNo = v; }
    public String getCurrentHandler() { return currentHandler; }
    public void setCurrentHandler(String v) { this.currentHandler = v; }
    public String getDeadline() { return deadline; }
    public void setDeadline(String v) { this.deadline = v; }
    public double getApprovedAmount() { return approvedAmount; }
    public void setApprovedAmount(double v) { this.approvedAmount = v; }
    public String getPaidAt() { return paidAt; }
    public void setPaidAt(String v) { this.paidAt = v; }
    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String v) { this.rejectionReason = v; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String v) { this.reviewNote = v; }
    public String getSlaDeadline() { return slaDeadline; }
    public void setSlaDeadline(String v) { this.slaDeadline = v; }
    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String v) { this.riskLevel = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
    public String getEnterpriseName() { return enterpriseName; }
    public void setEnterpriseName(String v) { this.enterpriseName = v; }
    public String getPersonName() { return personName; }
    public void setPersonName(String v) { this.personName = v; }
    public String getIdNumber() { return idNumber; }
    public void setIdNumber(String v) { this.idNumber = v; }
    public String getPositionName() { return positionName; }
    public void setPositionName(String v) { this.positionName = v; }
    public String getActualEmployerName() { return actualEmployerName; }
    public void setActualEmployerName(String v) { this.actualEmployerName = v; }
    public String getPolicyNo() { return policyNo; }
    public void setPolicyNo(String v) { this.policyNo = v; }
    public String getPlanName() { return planName; }
    public void setPlanName(String v) { this.planName = v; }
    public String getInsurer() { return insurer; }
    public void setInsurer(String v) { this.insurer = v; }
    public int getDocumentCount() { return documentCount; }
    public void setDocumentCount(int v) { this.documentCount = v; }
    public int getMissingCount() { return missingCount; }
    public void setMissingCount(int v) { this.missingCount = v; }
    public List<String> getMissingTypes() { return missingTypes; }
    public void setMissingTypes(List<String> v) { this.missingTypes = v; }
    public int getCompletePercent() { return completePercent; }
    public void setCompletePercent(int v) { this.completePercent = v; }
    public Integer getDeadlineDays() { return deadlineDays; }
    public void setDeadlineDays(Integer v) { this.deadlineDays = v; }
    public boolean isDeadlineOverdue() { return deadlineOverdue; }
    public void setDeadlineOverdue(boolean v) { this.deadlineOverdue = v; }
    public boolean isSlaOverdue() { return slaOverdue; }
    public void setSlaOverdue(boolean v) { this.slaOverdue = v; }
    public String getCalculatedRisk() { return calculatedRisk; }
    public void setCalculatedRisk(String v) { this.calculatedRisk = v; }
}
