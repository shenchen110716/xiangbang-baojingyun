package com.xbb.baojing.recharge;

import java.time.LocalDateTime;

/** Ports backend/models/finance_accounts.py::InsurerAccountLink. Maps an
 * insurer name (free text, matching InsurancePlan.insurer) to a collection
 * account — many-to-one, one insurer has at most one active link at a time,
 * enforced at the application layer in InsurerAccountController. */
public class InsurerAccountLink {
    private Integer id;
    private String insurer;
    // Nullable FK to insurers.id, backfilled from `insurer` by name match
    // (2026-07-24 insurer-portal migration) — see plan.Insurer.
    private Integer insurerId;
    private Integer accountId;
    private LocalDateTime createdAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getInsurer() { return insurer; }
    public void setInsurer(String v) { this.insurer = v; }
    public Integer getInsurerId() { return insurerId; }
    public void setInsurerId(Integer v) { this.insurerId = v; }
    public Integer getAccountId() { return accountId; }
    public void setAccountId(Integer v) { this.accountId = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
}
