package com.xbb.baojing.agent;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Java mirror of the Alembic-owned agent_commission_statements table — an
 * append-only ledger head. status: draft|confirmed|partially_paid|paid|void.
 * §5.3: 已确认结算项不得原地改写，差错通过调整项或冲正记录处理. */
public class AgentCommissionStatement {
    private Integer id;
    private Integer agentId;
    private String statementNo;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private String currency = "CNY";
    private double totalAmount = 0;
    private String status = "draft";
    private LocalDateTime confirmedAt;
    private LocalDateTime createdAt;

    public Integer getId() { return id; }
    public void setId(Integer v) { id = v; }
    public Integer getAgentId() { return agentId; }
    public void setAgentId(Integer v) { agentId = v; }
    public String getStatementNo() { return statementNo; }
    public void setStatementNo(String v) { statementNo = v; }
    public LocalDate getPeriodStart() { return periodStart; }
    public void setPeriodStart(LocalDate v) { periodStart = v; }
    public LocalDate getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(LocalDate v) { periodEnd = v; }
    public String getCurrency() { return currency; }
    public void setCurrency(String v) { currency = v; }
    public double getTotalAmount() { return totalAmount; }
    public void setTotalAmount(double v) { totalAmount = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public LocalDateTime getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(LocalDateTime v) { confirmedAt = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { createdAt = v; }
}
