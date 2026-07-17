package com.xbb.baojing.timeliness;

import java.time.LocalDateTime;

/** Java mirror of the Alembic-owned timeliness_outbox table.
 * status: pending|processing|done|failed. The active worker remains
 * Python-side (§12 allows retry); this mirror is read-only for parity. */
public class TimelinessOutbox {
    private Integer id;
    private Integer employmentFactId;
    private String reason = "";
    private String status = "pending";
    private int attempts = 0;
    private String lastError = "";
    private LocalDateTime createdAt;
    private LocalDateTime processedAt;

    public Integer getId() { return id; }
    public void setId(Integer v) { id = v; }
    public Integer getEmploymentFactId() { return employmentFactId; }
    public void setEmploymentFactId(Integer v) { employmentFactId = v; }
    public String getReason() { return reason; }
    public void setReason(String v) { reason = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int v) { attempts = v; }
    public String getLastError() { return lastError; }
    public void setLastError(String v) { lastError = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { createdAt = v; }
    public LocalDateTime getProcessedAt() { return processedAt; }
    public void setProcessedAt(LocalDateTime v) { processedAt = v; }
}
