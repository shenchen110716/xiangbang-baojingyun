package com.xbb.baojing.agent;

import java.time.LocalDateTime;

/** Java mirror of the Alembic-owned agent_commission_payments table — records
 * an actual platform payment event, independent of which statement(s) it
 * settles (allocation is a separate table, §5.3). */
public class AgentCommissionPayment {
    private Integer id;
    private Integer agentId;
    private double amount = 0;
    private String channel = "";
    private String transactionNo = "";
    private LocalDateTime paidAt;
    private String voucherUrl = "";
    private Integer createdBy;
    private LocalDateTime createdAt;

    public Integer getId() { return id; }
    public void setId(Integer v) { id = v; }
    public Integer getAgentId() { return agentId; }
    public void setAgentId(Integer v) { agentId = v; }
    public double getAmount() { return amount; }
    public void setAmount(double v) { amount = v; }
    public String getChannel() { return channel; }
    public void setChannel(String v) { channel = v; }
    public String getTransactionNo() { return transactionNo; }
    public void setTransactionNo(String v) { transactionNo = v; }
    public LocalDateTime getPaidAt() { return paidAt; }
    public void setPaidAt(LocalDateTime v) { paidAt = v; }
    public String getVoucherUrl() { return voucherUrl; }
    public void setVoucherUrl(String v) { voucherUrl = v; }
    public Integer getCreatedBy() { return createdBy; }
    public void setCreatedBy(Integer v) { createdBy = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { createdAt = v; }
}
