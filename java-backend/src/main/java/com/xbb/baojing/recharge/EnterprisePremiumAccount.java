package com.xbb.baojing.recharge;

/** Ports backend/models/finance_accounts.py::EnterprisePremiumAccount. The
 * balance is keyed on (enterprise, account) — not (enterprise, insurer) —
 * so insurers sharing one collection account naturally share one balance.
 * (enterpriseId, accountId) uniqueness is enforced at the application layer
 * by RechargeService.getOrCreatePremiumAccount(). */
public class EnterprisePremiumAccount {
    private Integer id;
    private Integer enterpriseId;
    private Integer accountId;
    private double balance = 0;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Integer getEnterpriseId() { return enterpriseId; }
    public void setEnterpriseId(Integer v) { this.enterpriseId = v; }
    public Integer getAccountId() { return accountId; }
    public void setAccountId(Integer v) { this.accountId = v; }
    public double getBalance() { return balance; }
    public void setBalance(double v) { this.balance = v; }
}
