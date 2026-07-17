package com.xbb.baojing.agent;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AgentCommissionStatementItemMapper {
    String COLUMNS = "id, statement_id as statementId, source_type as sourceType, " +
            "policy_member_id as policyMemberId, plan_id as planId, enterprise_id as enterpriseId, " +
            "period_start as periodStart, period_end as periodEnd, amount, " +
            "amount_snapshot_json as amountSnapshotJson, status, adjusts_item_id as adjustsItemId, " +
            "created_at as createdAt";

    @Select("SELECT " + COLUMNS + " FROM agent_commission_statement_items WHERE statement_id = #{statementId} " +
            "ORDER BY id")
    List<AgentCommissionStatementItem> findByStatement(Integer statementId);
}
