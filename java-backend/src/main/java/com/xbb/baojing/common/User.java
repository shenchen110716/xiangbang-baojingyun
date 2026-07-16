package com.xbb.baojing.common;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

public class User {
    private Integer id;
    private String username;
    private String passwordHash;
    private String name;
    private String role; // admin | enterprise | salesperson
    private Integer enterpriseId;
    private String enterpriseRole;
    private String phone = "";
    private String status = "active";
    private boolean active = true;
    private boolean owner = false;
    private int sessionVersion = 1;
    private LocalDateTime createdAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String v) { this.username = v; }

    // Matches UserOut in backend/schemas/auth.py — the hash must never
    // reach an HTTP response, only the JDBC layer (MyBatis reads/writes
    // this field directly by name, independent of Jackson visibility).
    @JsonIgnore
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String v) { this.passwordHash = v; }

    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getRole() { return role; }
    public void setRole(String v) { this.role = v; }
    public Integer getEnterpriseId() { return enterpriseId; }
    public void setEnterpriseId(Integer v) { this.enterpriseId = v; }
    @JsonProperty("enterprise_role")
    public String getEnterpriseRole() { return enterpriseRole; }
    public void setEnterpriseRole(String v) { this.enterpriseRole = v; }
    public String getPhone() { return phone; }
    public void setPhone(String v) { this.phone = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public boolean isActive() { return active; }
    public void setActive(boolean v) { this.active = v; }

    // "owner" (from isOwner()/setOwner()) is the JavaBean property name
    // Jackson infers by stripping "is" — @JsonProperty forces the is_owner
    // key the frontend actually expects (web/src/api/types.ts User.is_owner).
    @JsonProperty("is_owner")
    public boolean isOwner() { return owner; }
    public void setOwner(boolean v) { this.owner = v; }

    public int getSessionVersion() { return sessionVersion; }
    public void setSessionVersion(int v) { this.sessionVersion = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
}
