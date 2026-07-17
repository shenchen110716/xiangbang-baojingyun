package com.xbb.baojing.agent;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AgentCommissionPaymentMapper {
    String COLUMNS = "id, agent_id as agentId, amount, channel, transaction_no as transactionNo, " +
            "paid_at as paidAt, voucher_url as voucherUrl, created_by as createdBy, created_at as createdAt";

    @Select("SELECT " + COLUMNS + " FROM agent_commission_payments WHERE agent_id = #{agentId} " +
            "ORDER BY id DESC")
    List<AgentCommissionPayment> findByAgent(Integer agentId);
}
