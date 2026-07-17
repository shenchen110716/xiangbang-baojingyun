package com.xbb.baojing.agent;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Java mirror of the Alembic-owned agent_commission_statement_items table.
 * source_type: accrual|adjustment|reversal. status: draft|confirmed|void.
 * A confirmed item is never rewritten; adjusts_item_id points at the row a
 * correction amends, so the original stays intact for audit (§5.3). */
public class AgentCommissionStatementItem {
    private Integer id;
    private Integer statementId;
    private String sourceType = "accrual";
    private Integer policyMemberId;
    private Integer planId;
    private Integer enterpriseId;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private double amount = 0;
    private String amountSnapshotJson = "";
    private String status = "draft";
    private Integer adjustsItemId;
    private LocalDateTime createdAt;

    public Integer getId() { return id; }
    public void setId(Integer v) { id = v; }
    public Integer getStatementId() { return statementId; }
    public void setStatementId(Integer v) { statementId = v; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String v) { sourceType = v; }
    public Integer getPolicyMemberId() { return policyMemberId; }
    public void setPolicyMemberId(Integer v) { policyMemberId = v; }
    public Integer getPlanId() { return planId; }
    public void setPlanId(Integer v) { planId = v; }
    public Integer getEnterpriseId() { return enterpriseId; }
    public void setEnterpriseId(Integer v) { enterpriseId = v; }
    public LocalDate getPeriodStart() { return periodStart; }
    public void setPeriodStart(LocalDate v) { periodStart = v; }
    public LocalDate getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(LocalDate v) { periodEnd = v; }
    public double getAmount() { return amount; }
    public void setAmount(double v) { amount = v; }
    public String getAmountSnapshotJson() { return amountSnapshotJson; }
    public void setAmountSnapshotJson(String v) { amountSnapshotJson = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public Integer getAdjustsItemId() { return adjustsItemId; }
    public void setAdjustsItemId(Integer v) { adjustsItemId = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { createdAt = v; }
}
