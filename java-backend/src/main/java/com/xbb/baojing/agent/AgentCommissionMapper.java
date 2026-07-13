package com.xbb.baojing.agent;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface AgentCommissionMapper {
    String COLS = "id, agent_id as agentId, enterprise_id as enterpriseId, plan_id as planId, rate, mode, " +
            "markup_amount as markupAmount, sale_price as salePrice, status, created_at as createdAt";

    @Select("SELECT " + COLS + " FROM agent_commissions ORDER BY id DESC")
    List<AgentCommission> findAll();

    @Select("SELECT " + COLS + " FROM agent_commissions WHERE id = #{id}")
    AgentCommission findById(Integer id);

    @Select("SELECT " + COLS + " FROM agent_commissions WHERE agent_id = #{agentId} ORDER BY id DESC")
    List<AgentCommission> findByAgent(Integer agentId);

    @Select("SELECT " + COLS + " FROM agent_commissions WHERE enterprise_id = #{enterpriseId} ORDER BY id DESC")
    List<AgentCommission> findByEnterprise(Integer enterpriseId);

    @Select("SELECT " + COLS + " FROM agent_commissions WHERE enterprise_id = #{enterpriseId} ORDER BY id ASC LIMIT 1")
    AgentCommission findFirstByEnterprise(Integer enterpriseId);

    @Select("SELECT " + COLS + " FROM agent_commissions WHERE enterprise_id = #{enterpriseId} AND plan_id = #{planId} AND status = 'active' ORDER BY id DESC LIMIT 1")
    AgentCommission findActiveRelation(@Param("enterpriseId") Integer enterpriseId, @Param("planId") Integer planId);

    @Insert("INSERT INTO agent_commissions (agent_id, enterprise_id, plan_id, rate, mode, markup_amount, sale_price, status, created_at) " +
            "VALUES (#{agentId}, #{enterpriseId}, #{planId}, #{rate}, #{mode}, #{markupAmount}, #{salePrice}, #{status}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AgentCommission c);

    @Update("UPDATE agent_commissions SET rate=#{rate}, mode=#{mode}, markup_amount=#{markupAmount}, sale_price=#{salePrice}, status=#{status} WHERE id=#{id}")
    int update(AgentCommission c);

    @Delete("DELETE FROM agent_commissions WHERE id = #{id}")
    int delete(Integer id);
}
