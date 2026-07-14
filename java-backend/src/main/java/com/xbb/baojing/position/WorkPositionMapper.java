package com.xbb.baojing.position;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface WorkPositionMapper {
    String COLS = "id, enterprise_id as enterpriseId, actual_employer_id as actualEmployerId, actual_employer as actualEmployer, " +
            "name, occupation_class as occupationClass, plan_id as planId, status, created_by as createdBy, created_at as createdAt";

    @Select("<script>SELECT " + COLS + " FROM work_positions WHERE 1=1 " +
            "<if test='enterpriseId != null'>AND enterprise_id = #{enterpriseId}</if> ORDER BY id DESC</script>")
    List<WorkPosition> search(Integer enterpriseId);

    @Select("SELECT " + COLS + " FROM work_positions WHERE id = #{id}")
    WorkPosition findById(Integer id);

    @Insert("INSERT INTO work_positions (enterprise_id, actual_employer_id, actual_employer, name, occupation_class, plan_id, status, created_by, created_at) " +
            "VALUES (#{enterpriseId}, #{actualEmployerId}, #{actualEmployer}, #{name}, #{occupationClass}, #{planId}, #{status}, #{createdBy}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(WorkPosition p);

    @Update("UPDATE work_positions SET actual_employer_id=#{actualEmployerId}, actual_employer=#{actualEmployer}, name=#{name}, " +
            "occupation_class=#{occupationClass}, plan_id=#{planId}, status=#{status} WHERE id=#{id}")
    int update(WorkPosition p);

    @Delete("DELETE FROM work_positions WHERE id = #{id}")
    int delete(Integer id);

    @Select("SELECT COUNT(*) FROM insured_people WHERE position_id = #{id}")
    int countInsured(Integer id);
}
