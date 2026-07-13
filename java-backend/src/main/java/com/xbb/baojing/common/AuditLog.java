package com.xbb.baojing.common;

import java.time.LocalDateTime;

public class AuditLog {
    private Integer id;
    private Integer userId;
    private String operator; // joined, not persisted
    private String action;
    private String objectType;
    private String objectId;
    private String detail = "";
    private LocalDateTime createdAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Integer getUserId() { return userId; }
    public void setUserId(Integer v) { this.userId = v; }
    public String getOperator() { return operator; }
    public void setOperator(String v) { this.operator = v; }
    public String getAction() { return action; }
    public void setAction(String v) { this.action = v; }
    public String getObjectType() { return objectType; }
    public void setObjectType(String v) { this.objectType = v; }
    public String getObjectId() { return objectId; }
    public void setObjectId(String v) { this.objectId = v; }
    public String getDetail() { return detail; }
    public void setDetail(String v) { this.detail = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
}
