package com.xbb.baojing.timeliness;

import java.time.LocalDateTime;

/** Java mirror of the Alembic-owned employment_timeliness_results table.
 * Versioned, not mutable: a recalc supersedes the old row rather than editing
 * it (§12 — at most one status='current' row per idempotency key).
 * timeliness_status: timely|early|late|missing|premature|pending|unmatched|conflict.
 * responsibility_reason: source_feedback_late|operator_processing_late|
 * system_processing_late|insurer_confirmation_late|unassigned_responsibility|normal.
 * status: current|superseded. */
public class EmploymentTimelinessResult {
    private Integer id;
    private Integer employmentFactId;
    private int employmentFactRevisionNo;
    private String operationType;
    private Integer enterpriseId;
    private Integer actualEmployerId;
    private Integer personId;
    private Integer responsibleUserId;
    private Integer primaryManagerUserId;
    private LocalDateTime actualBusinessAt;
    private LocalDateTime expectedCoverageAt;
    private LocalDateTime actualCoverageAt;
    private String timelinessStatus;
    private int delaySeconds = 0;
    private int earlySeconds = 0;
    private int coverageGapSeconds = 0;
    private double excessPremium = 0;
    private double earlyPremium = 0;
    private String feedbackStatus = "";
    private LocalDateTime feedbackDeadlineAt;
    private String responsibilityReason = "normal";
    private String responsibilityEvidenceJson = "";
    private int productRuleVersion = 1;
    private int calculationVersion = 1;
    private LocalDateTime calculatedAt;
    private String status = "current";

    public Integer getId() { return id; }
    public void setId(Integer v) { id = v; }
    public Integer getEmploymentFactId() { return employmentFactId; }
    public void setEmploymentFactId(Integer v) { employmentFactId = v; }
    public int getEmploymentFactRevisionNo() { return employmentFactRevisionNo; }
    public void setEmploymentFactRevisionNo(int v) { employmentFactRevisionNo = v; }
    public String getOperationType() { return operationType; }
    public void setOperationType(String v) { operationType = v; }
    public Integer getEnterpriseId() { return enterpriseId; }
    public void setEnterpriseId(Integer v) { enterpriseId = v; }
    public Integer getActualEmployerId() { return actualEmployerId; }
    public void setActualEmployerId(Integer v) { actualEmployerId = v; }
    public Integer getPersonId() { return personId; }
    public void setPersonId(Integer v) { personId = v; }
    public Integer getResponsibleUserId() { return responsibleUserId; }
    public void setResponsibleUserId(Integer v) { responsibleUserId = v; }
    public Integer getPrimaryManagerUserId() { return primaryManagerUserId; }
    public void setPrimaryManagerUserId(Integer v) { primaryManagerUserId = v; }
    public LocalDateTime getActualBusinessAt() { return actualBusinessAt; }
    public void setActualBusinessAt(LocalDateTime v) { actualBusinessAt = v; }
    public LocalDateTime getExpectedCoverageAt() { return expectedCoverageAt; }
    public void setExpectedCoverageAt(LocalDateTime v) { expectedCoverageAt = v; }
    public LocalDateTime getActualCoverageAt() { return actualCoverageAt; }
    public void setActualCoverageAt(LocalDateTime v) { actualCoverageAt = v; }
    public String getTimelinessStatus() { return timelinessStatus; }
    public void setTimelinessStatus(String v) { timelinessStatus = v; }
    public int getDelaySeconds() { return delaySeconds; }
    public void setDelaySeconds(int v) { delaySeconds = v; }
    public int getEarlySeconds() { return earlySeconds; }
    public void setEarlySeconds(int v) { earlySeconds = v; }
    public int getCoverageGapSeconds() { return coverageGapSeconds; }
    public void setCoverageGapSeconds(int v) { coverageGapSeconds = v; }
    public double getExcessPremium() { return excessPremium; }
    public void setExcessPremium(double v) { excessPremium = v; }
    public double getEarlyPremium() { return earlyPremium; }
    public void setEarlyPremium(double v) { earlyPremium = v; }
    public String getFeedbackStatus() { return feedbackStatus; }
    public void setFeedbackStatus(String v) { feedbackStatus = v; }
    public LocalDateTime getFeedbackDeadlineAt() { return feedbackDeadlineAt; }
    public void setFeedbackDeadlineAt(LocalDateTime v) { feedbackDeadlineAt = v; }
    public String getResponsibilityReason() { return responsibilityReason; }
    public void setResponsibilityReason(String v) { responsibilityReason = v; }
    public String getResponsibilityEvidenceJson() { return responsibilityEvidenceJson; }
    public void setResponsibilityEvidenceJson(String v) { responsibilityEvidenceJson = v; }
    public int getProductRuleVersion() { return productRuleVersion; }
    public void setProductRuleVersion(int v) { productRuleVersion = v; }
    public int getCalculationVersion() { return calculationVersion; }
    public void setCalculationVersion(int v) { calculationVersion = v; }
    public LocalDateTime getCalculatedAt() { return calculatedAt; }
    public void setCalculatedAt(LocalDateTime v) { calculatedAt = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
}
