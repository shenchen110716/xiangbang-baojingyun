package com.xbb.baojing.employment;

import java.time.LocalDateTime;

/** Java mirror of the Alembic-owned employment_feedback_batches table.
 * status: uploaded|previewed|confirmed|imported_pending_calculation|completed|rejected|failed
 * source_type: manual_import|api|system_sync */
public class EmploymentFeedbackBatch {
    private Integer id;
    private Integer enterpriseId;
    private Integer actualEmployerId;
    private String sourceType;
    private String sourceFilename = "";
    private String sourceFileHash = "";
    private String sourceFilePath = "";
    private LocalDateTime reportedAt;
    private Integer importedBy;
    private LocalDateTime importedAt;
    private int totalRows = 0;
    private int validRows = 0;
    private int invalidRows = 0;
    private String status = "uploaded";
    private int previewVersion = 0;
    private String confirmTokenDigest;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Integer getId() { return id; }
    public void setId(Integer v) { id = v; }
    public Integer getEnterpriseId() { return enterpriseId; }
    public void setEnterpriseId(Integer v) { enterpriseId = v; }
    public Integer getActualEmployerId() { return actualEmployerId; }
    public void setActualEmployerId(Integer v) { actualEmployerId = v; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String v) { sourceType = v; }
    public String getSourceFilename() { return sourceFilename; }
    public void setSourceFilename(String v) { sourceFilename = v; }
    public String getSourceFileHash() { return sourceFileHash; }
    public void setSourceFileHash(String v) { sourceFileHash = v; }
    public String getSourceFilePath() { return sourceFilePath; }
    public void setSourceFilePath(String v) { sourceFilePath = v; }
    public LocalDateTime getReportedAt() { return reportedAt; }
    public void setReportedAt(LocalDateTime v) { reportedAt = v; }
    public Integer getImportedBy() { return importedBy; }
    public void setImportedBy(Integer v) { importedBy = v; }
    public LocalDateTime getImportedAt() { return importedAt; }
    public void setImportedAt(LocalDateTime v) { importedAt = v; }
    public int getTotalRows() { return totalRows; }
    public void setTotalRows(int v) { totalRows = v; }
    public int getValidRows() { return validRows; }
    public void setValidRows(int v) { validRows = v; }
    public int getInvalidRows() { return invalidRows; }
    public void setInvalidRows(int v) { invalidRows = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public int getPreviewVersion() { return previewVersion; }
    public void setPreviewVersion(int v) { previewVersion = v; }
    public String getConfirmTokenDigest() { return confirmTokenDigest; }
    public void setConfirmTokenDigest(String v) { confirmTokenDigest = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { createdAt = v; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime v) { updatedAt = v; }
}
