package com.xbb.baojing.employment;

import java.time.LocalDateTime;

/** Java mirror of the Alembic-owned employment_facts table — the authoritative,
 * append-only fact base. status: active|superseded|pending_match|conflict|voided.
 * §20.6: only 'active' rows may feed a published timeliness rate. */
public class EmploymentFact {
    private Integer id;
    private Integer enterpriseId;
    private Integer actualEmployerId;
    private Integer personId;
    private String externalEmployeeNo = "";
    private String externalEmploymentId = "";
    private String idNumberHash = "";
    private String idNumberCipher = "";
    private String personName = "";
    private LocalDateTime actualHireAt;
    private LocalDateTime actualLeaveAt;
    private LocalDateTime feedbackReportedAt;
    private Integer batchId;
    private String sourceEventId;
    private int revisionNo = 1;
    private Integer previousVersionId;
    private String status = "active";
    private Integer createdBy;
    private LocalDateTime createdAt;

    public Integer getId() { return id; }
    public void setId(Integer v) { id = v; }
    public Integer getEnterpriseId() { return enterpriseId; }
    public void setEnterpriseId(Integer v) { enterpriseId = v; }
    public Integer getActualEmployerId() { return actualEmployerId; }
    public void setActualEmployerId(Integer v) { actualEmployerId = v; }
    public Integer getPersonId() { return personId; }
    public void setPersonId(Integer v) { personId = v; }
    public String getExternalEmployeeNo() { return externalEmployeeNo; }
    public void setExternalEmployeeNo(String v) { externalEmployeeNo = v; }
    public String getExternalEmploymentId() { return externalEmploymentId; }
    public void setExternalEmploymentId(String v) { externalEmploymentId = v; }
    public String getIdNumberHash() { return idNumberHash; }
    public void setIdNumberHash(String v) { idNumberHash = v; }
    public String getIdNumberCipher() { return idNumberCipher; }
    public void setIdNumberCipher(String v) { idNumberCipher = v; }
    public String getPersonName() { return personName; }
    public void setPersonName(String v) { personName = v; }
    public LocalDateTime getActualHireAt() { return actualHireAt; }
    public void setActualHireAt(LocalDateTime v) { actualHireAt = v; }
    public LocalDateTime getActualLeaveAt() { return actualLeaveAt; }
    public void setActualLeaveAt(LocalDateTime v) { actualLeaveAt = v; }
    public LocalDateTime getFeedbackReportedAt() { return feedbackReportedAt; }
    public void setFeedbackReportedAt(LocalDateTime v) { feedbackReportedAt = v; }
    public Integer getBatchId() { return batchId; }
    public void setBatchId(Integer v) { batchId = v; }
    public String getSourceEventId() { return sourceEventId; }
    public void setSourceEventId(String v) { sourceEventId = v; }
    public int getRevisionNo() { return revisionNo; }
    public void setRevisionNo(int v) { revisionNo = v; }
    public Integer getPreviousVersionId() { return previousVersionId; }
    public void setPreviousVersionId(Integer v) { previousVersionId = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public Integer getCreatedBy() { return createdBy; }
    public void setCreatedBy(Integer v) { createdBy = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { createdAt = v; }
}
