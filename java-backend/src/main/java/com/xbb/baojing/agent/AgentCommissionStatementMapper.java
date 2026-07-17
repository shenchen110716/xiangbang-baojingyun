package com.xbb.baojing.agent;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AgentCommissionStatementMapper {
    String COLUMNS = "id, agent_id as agentId, statement_no as statementNo, period_start as periodStart, " +
            "period_end as periodEnd, currency, total_amount as totalAmount, status, " +
            "confirmed_at as confirmedAt, created_at as createdAt";

    @Select("SELECT " + COLUMNS + " FROM agent_commission_statements WHERE id = #{id}")
    AgentCommissionStatement findById(Integer id);

    // §17.1 业务员只能看自己的结算单；agent_id 必须来自认证身份，不接受调用方传参。
    @Select("SELECT " + COLUMNS + " FROM agent_commission_statements WHERE agent_id = #{agentId} " +
            "ORDER BY id DESC")
    List<AgentCommissionStatement> findByAgent(Integer agentId);
}
