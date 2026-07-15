package com.xbb.baojing.recharge;

import java.time.LocalDateTime;
import java.util.List;

/** Ports backend/models/finance_accounts.py::InsurerAccount. A collection
 * account can be shared by multiple insurers (see InsurerAccountLink), so
 * the account itself carries no insurer field — it is the balance-bearing
 * entity, insurers are just labels linked onto it. */
public class InsurerAccount {
    private Integer id;
    private String label = "";
    private String bankName = "";
    private String accountNo = "";
    private String accountHolder = "";
    private String status = "active";
    private LocalDateTime createdAt;

    // response-only, joined
    private List<String> insurers;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getLabel() { return label; }
    public void setLabel(String v) { this.label = v; }
    public String getBankName() { return bankName; }
    public void setBankName(String v) { this.bankName = v; }
    public String getAccountNo() { return accountNo; }
    public void setAccountNo(String v) { this.accountNo = v; }
    public String getAccountHolder() { return accountHolder; }
    public void setAccountHolder(String v) { this.accountHolder = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
    public List<String> getInsurers() { return insurers; }
    public void setInsurers(List<String> v) { this.insurers = v; }
}
