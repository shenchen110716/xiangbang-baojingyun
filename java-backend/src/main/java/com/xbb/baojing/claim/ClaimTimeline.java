package com.xbb.baojing.claim;

import java.time.LocalDateTime;

public class ClaimTimeline {
    private Integer id;
    private Integer claimId;
    private String node;
    private String action;
    private String note = "";
    private String operator = "系统";
    private LocalDateTime createdAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Integer getClaimId() { return claimId; }
    public void setClaimId(Integer v) { this.claimId = v; }
    public String getNode() { return node; }
    public void setNode(String v) { this.node = v; }
    public String getAction() { return action; }
    public void setAction(String v) { this.action = v; }
    public String getNote() { return note; }
    public void setNote(String v) { this.note = v; }
    public String getOperator() { return operator; }
    public void setOperator(String v) { this.operator = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
}
