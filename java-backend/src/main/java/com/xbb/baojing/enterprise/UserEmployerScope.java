package com.xbb.baojing.enterprise;

import java.time.LocalDateTime;

/** Java mirror of the Alembic-owned user_employer_scopes table. */
public class UserEmployerScope {
    private Integer id;
    private Integer userId;
    private Integer enterpriseId;
    private Integer actualEmployerId;
    private String responsibilityType;
    private Integer grantedBy;
    private LocalDateTime assignedAt;
    private LocalDateTime revokedAt;
    private String status;
    private LocalDateTime createdAt;

    public Integer getId() { return id; }
    public void setId(Integer v) { id = v; }
    public Integer getUserId() { return userId; }
    public void setUserId(Integer v) { userId = v; }
    public Integer getEnterpriseId() { return enterpriseId; }
    public void setEnterpriseId(Integer v) { enterpriseId = v; }
    public Integer getActualEmployerId() { return actualEmployerId; }
    public void setActualEmployerId(Integer v) { actualEmployerId = v; }
    public String getResponsibilityType() { return responsibilityType; }
    public void setResponsibilityType(String v) { responsibilityType = v; }
    public Integer getGrantedBy() { return grantedBy; }
    public void setGrantedBy(Integer v) { grantedBy = v; }
    public LocalDateTime getAssignedAt() { return assignedAt; }
    public void setAssignedAt(LocalDateTime v) { assignedAt = v; }
    public LocalDateTime getRevokedAt() { return revokedAt; }
    public void setRevokedAt(LocalDateTime v) { revokedAt = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { createdAt = v; }
}
