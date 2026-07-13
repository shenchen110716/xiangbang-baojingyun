package com.xbb.baojing.enterprise;

import java.time.LocalDateTime;

public class ActualEmployer {
    private Integer id;
    private Integer enterpriseId;
    private String name;
    private String creditCode = "";
    private String contact = "";
    private String phone = "";
    private String status = "active";
    private LocalDateTime createdAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Integer getEnterpriseId() { return enterpriseId; }
    public void setEnterpriseId(Integer v) { this.enterpriseId = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getCreditCode() { return creditCode; }
    public void setCreditCode(String v) { this.creditCode = v; }
    public String getContact() { return contact; }
    public void setContact(String v) { this.contact = v; }
    public String getPhone() { return phone; }
    public void setPhone(String v) { this.phone = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
}
