package com.xbb.baojing.claim;

public class ChecklistItem {
    private String docType;
    private String name;
    private boolean required = true;
    private boolean uploaded;
    private String status;
    private String reviewNote = "";

    public String getDocType() { return docType; }
    public void setDocType(String v) { this.docType = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public boolean isRequired() { return required; }
    public void setRequired(boolean v) { this.required = v; }
    public boolean isUploaded() { return uploaded; }
    public void setUploaded(boolean v) { this.uploaded = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String v) { this.reviewNote = v; }
}
