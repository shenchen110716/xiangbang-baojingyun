package com.xbb.baojing.agent;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AgentCommissionPaymentAllocationMapper {
    String COLUMNS = "id, payment_id as paymentId, statement_id as statementId, amount, created_at as createdAt";

    @Select("SELECT " + COLUMNS + " FROM agent_commission_payment_allocations WHERE statement_id = #{statementId}")
    List<AgentCommissionPaymentAllocation> findByStatement(Integer statementId);

    @Select("SELECT " + COLUMNS + " FROM agent_commission_payment_allocations WHERE payment_id = #{paymentId}")
    List<AgentCommissionPaymentAllocation> findByPayment(Integer paymentId);
}
