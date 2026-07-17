package com.xbb.baojing.timeliness;

import java.time.LocalDateTime;

/** Java mirror of the Alembic-owned participation_operations table. Written
 * once and never updated: 即使人员或负责人之后调岗，历史操作归属也不能改变（§8）.
 * operation_type: enrollment|termination. */
public class ParticipationOperation {
    private Integer id;
    private Integer enterpriseId;
    private Integer actualEmployerId;
    private Integer personId;
    private String operationType;
    private Integer submittedBy;
    private Integer batchId;
    private Integer planId;
    private String ruleSnapshotJson = "";
    private LocalDateTime submittedAt;
    private LocalDateTime expectedAt;
    private LocalDateTime insurerConfirmedAt;
    private LocalDateTime systemSentAt;

    public Integer getId() { return id; }
    public void setId(Integer v) { id = v; }
    public Integer getEnterpriseId() { return enterpriseId; }
    public void setEnterpriseId(Integer v) { enterpriseId = v; }
    public Integer getActualEmployerId() { return actualEmployerId; }
    public void setActualEmployerId(Integer v) { actualEmployerId = v; }
    public Integer getPersonId() { return personId; }
    public void setPersonId(Integer v) { personId = v; }
    public String getOperationType() { return operationType; }
    public void setOperationType(String v) { operationType = v; }
    public Integer getSubmittedBy() { return submittedBy; }
    public void setSubmittedBy(Integer v) { submittedBy = v; }
    public Integer getBatchId() { return batchId; }
    public void setBatchId(Integer v) { batchId = v; }
    public Integer getPlanId() { return planId; }
    public void setPlanId(Integer v) { planId = v; }
    public String getRuleSnapshotJson() { return ruleSnapshotJson; }
    public void setRuleSnapshotJson(String v) { ruleSnapshotJson = v; }
    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(LocalDateTime v) { submittedAt = v; }
    public LocalDateTime getExpectedAt() { return expectedAt; }
    public void setExpectedAt(LocalDateTime v) { expectedAt = v; }
    public LocalDateTime getInsurerConfirmedAt() { return insurerConfirmedAt; }
    public void setInsurerConfirmedAt(LocalDateTime v) { insurerConfirmedAt = v; }
    public LocalDateTime getSystemSentAt() { return systemSentAt; }
    public void setSystemSentAt(LocalDateTime v) { systemSentAt = v; }
}
