package com.xbb.baojing.claim;

import java.time.LocalDateTime;

public class ClaimDocument {
    private Integer id;
    private Integer claimId;
    private String name;
    private String url = "";
    private String docType = "other";
    private String status = "uploaded";
    private String reviewNote = "";
    private LocalDateTime createdAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Integer getClaimId() { return claimId; }
    public void setClaimId(Integer v) { this.claimId = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getUrl() { return url; }
    public void setUrl(String v) { this.url = v; }
    public String getDocType() { return docType; }
    public void setDocType(String v) { this.docType = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String v) { this.reviewNote = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
}
