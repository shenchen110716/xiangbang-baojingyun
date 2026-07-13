package com.xbb.baojing.insured;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface PolicyMapper {
    String COLS = "id, policy_no as policyNo, enterprise_id as enterpriseId, plan_id as planId, premium, status, " +
            "start_date as startDate, end_date as endDate, created_at as createdAt";

    @Select("<script>SELECT " + COLS + " FROM policies WHERE 1=1 " +
            "<if test='enterpriseId != null'>AND enterprise_id = #{enterpriseId}</if> ORDER BY id DESC</script>")
    List<Policy> search(Integer enterpriseId);

    @Select("SELECT " + COLS + " FROM policies WHERE id = #{id}")
    Policy findById(Integer id);

    @Select("SELECT " + COLS + " FROM policies WHERE enterprise_id = #{enterpriseId} AND plan_id = #{planId} ORDER BY id ASC LIMIT 1")
    Policy findByEnterpriseAndPlan(@Param("enterpriseId") Integer enterpriseId, @Param("planId") Integer planId);

    @Insert("INSERT INTO policies (policy_no, enterprise_id, plan_id, premium, status, start_date, end_date, created_at) " +
            "VALUES (#{policyNo}, #{enterpriseId}, #{planId}, #{premium}, #{status}, #{startDate}, #{endDate}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Policy p);

    @Select("SELECT premium FROM policies WHERE enterprise_id = #{enterpriseId} AND plan_id = #{planId}")
    List<Double> findPremiumsForEnterprisePlan(@Param("enterpriseId") Integer enterpriseId, @Param("planId") Integer planId);
}
