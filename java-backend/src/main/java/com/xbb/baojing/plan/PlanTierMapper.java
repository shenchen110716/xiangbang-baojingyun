package com.xbb.baojing.plan;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface PlanTierMapper {
    String COLS = "id, plan_id as planId, occupation_class as occupationClass, price, coverage, status, created_at as createdAt";

    @Select("<script>SELECT " + COLS + " FROM plan_tiers WHERE 1=1 " +
            "<if test='planId != null'>AND plan_id = #{planId}</if> ORDER BY id DESC</script>")
    List<PlanTier> search(Integer planId);

    @Select("SELECT " + COLS + " FROM plan_tiers WHERE plan_id = #{planId} AND occupation_class = #{occupationClass} AND status = 'active' ORDER BY id DESC LIMIT 1")
    PlanTier findActiveTier(@Param("planId") Integer planId, @Param("occupationClass") String occupationClass);

    @Insert("INSERT INTO plan_tiers (plan_id, occupation_class, price, coverage, status, created_at) " +
            "VALUES (#{planId}, #{occupationClass}, #{price}, #{coverage}, #{status}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(PlanTier t);
}
