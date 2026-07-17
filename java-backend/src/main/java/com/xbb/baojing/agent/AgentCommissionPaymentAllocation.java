package com.xbb.baojing.agent;

import java.time.LocalDateTime;

/** Java mirror of the Alembic-owned agent_commission_payment_allocations
 * table. One statement may be paid in instalments and one payment may clear
 * several statements (§5.3); the balance ceilings on both sides are enforced
 * in the service layer, not by a single CHECK constraint. */
public class AgentCommissionPaymentAllocation {
    private Integer id;
    private Integer paymentId;
    private Integer statementId;
    private double amount;
    private LocalDateTime createdAt;

    public Integer getId() { return id; }
    public void setId(Integer v) { id = v; }
    public Integer getPaymentId() { return paymentId; }
    public void setPaymentId(Integer v) { paymentId = v; }
    public Integer getStatementId() { return statementId; }
    public void setStatementId(Integer v) { statementId = v; }
    public double getAmount() { return amount; }
    public void setAmount(double v) { amount = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { createdAt = v; }
}
