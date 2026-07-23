package com.xbb.baojing.plan;

import java.time.LocalDateTime;

/** Ports backend/models/insurer.py::Insurer (2026-07-24 insurer-portal
 * feature). name/contact/phone are the currently-effective values; pending_*
 * hold a submitted-but-not-yet-approved edit (two-stage flow, see
 * backend/routers/insurers.py::review_insurer_edit) and are non-null only
 * while a change is awaiting platform review. */
public class Insurer {
    private Integer id;
    private String name;
    private String contact = "";
    private String phone = "";
    private String status = "active";
    private String pendingName;
    private String pendingContact;
    private String pendingPhone;
    private LocalDateTime pendingSubmittedAt;
    private LocalDateTime createdAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getContact() { return contact; }
    public void setContact(String v) { this.contact = v; }
    public String getPhone() { return phone; }
    public void setPhone(String v) { this.phone = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getPendingName() { return pendingName; }
    public void setPendingName(String v) { this.pendingName = v; }
    public String getPendingContact() { return pendingContact; }
    public void setPendingContact(String v) { this.pendingContact = v; }
    public String getPendingPhone() { return pendingPhone; }
    public void setPendingPhone(String v) { this.pendingPhone = v; }
    public LocalDateTime getPendingSubmittedAt() { return pendingSubmittedAt; }
    public void setPendingSubmittedAt(LocalDateTime v) { this.pendingSubmittedAt = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
}
