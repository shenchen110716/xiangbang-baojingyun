package com.xbb.baojing.enterprise;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface EnterpriseMapper {
    String COLS = "id, name, kind, credit_code as creditCode, contact, phone, status, agent_id as agentId, " +
            "premium_balance as premiumBalance, usage_balance as usageBalance, usage_fee_daily as usageFeeDaily, " +
            "alert_days as alertDays, created_at as createdAt";

    @Select("<script>SELECT " + COLS + " FROM enterprises WHERE 1=1 " +
            "<if test='enterpriseId != null'>AND id = #{enterpriseId}</if> " +
            "<if test='q != null and q != \"\"'>AND (name LIKE CONCAT('%',#{q},'%') OR contact LIKE CONCAT('%',#{q},'%'))</if> " +
            "<if test='status != null and status != \"\"'>AND status = #{status}</if> " +
            "ORDER BY id DESC</script>")
    List<Enterprise> search(@Param("enterpriseId") Integer enterpriseId, @Param("q") String q, @Param("status") String status);

    @Select("SELECT " + COLS + " FROM enterprises WHERE id = #{id}")
    Enterprise findById(Integer id);

    @Select("SELECT " + COLS + " FROM enterprises ORDER BY id ASC LIMIT 1")
    Enterprise findFirst();

    @Select("SELECT " + COLS + " FROM enterprises WHERE name = #{name} LIMIT 1")
    Enterprise findByName(String name);

    @Insert("INSERT INTO enterprises (name, kind, credit_code, contact, phone, status, agent_id, premium_balance, usage_balance, usage_fee_daily, alert_days, created_at) " +
            "VALUES (#{name}, #{kind}, #{creditCode}, #{contact}, #{phone}, #{status}, #{agentId}, #{premiumBalance}, #{usageBalance}, #{usageFeeDaily}, #{alertDays}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Enterprise e);

    @Update("UPDATE enterprises SET name=#{name}, kind=#{kind}, credit_code=#{creditCode}, contact=#{contact}, phone=#{phone}, " +
            "status=#{status}, agent_id=#{agentId}, premium_balance=#{premiumBalance}, usage_balance=#{usageBalance}, " +
            "usage_fee_daily=#{usageFeeDaily}, alert_days=#{alertDays} WHERE id=#{id}")
    int update(Enterprise e);

    @Delete("DELETE FROM enterprises WHERE id = #{id}")
    int delete(Integer id);
}
