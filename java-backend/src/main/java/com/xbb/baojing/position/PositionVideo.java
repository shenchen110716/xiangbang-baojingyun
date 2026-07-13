package com.xbb.baojing.position;

import java.time.LocalDateTime;

public class PositionVideo {
    private Integer id;
    private Integer positionId;
    private String name;
    private String url = "";
    private String status = "pending";
    private String reviewNote = "";
    private LocalDateTime createdAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Integer getPositionId() { return positionId; }
    public void setPositionId(Integer v) { this.positionId = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getUrl() { return url; }
    public void setUrl(String v) { this.url = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String v) { this.reviewNote = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
}
