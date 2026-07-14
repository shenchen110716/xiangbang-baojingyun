package com.xbb.baojing.enterprise;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface ActualEmployerMapper {
    String COLS = "id, enterprise_id as enterpriseId, name, credit_code as creditCode, contact, phone, status, created_at as createdAt";

    @Select("<script>SELECT " + COLS + " FROM actual_employers WHERE 1=1 " +
            "<if test='enterpriseId != null'>AND enterprise_id = #{enterpriseId}</if> ORDER BY id DESC</script>")
    List<ActualEmployer> search(@Param("enterpriseId") Integer enterpriseId);

    @Select("SELECT " + COLS + " FROM actual_employers WHERE id = #{id}")
    ActualEmployer findById(Integer id);

    @Select("SELECT " + COLS + " FROM actual_employers WHERE enterprise_id = #{enterpriseId} AND name = #{name} LIMIT 1")
    ActualEmployer findByEnterpriseAndName(@Param("enterpriseId") Integer enterpriseId, @Param("name") String name);

    @Insert("INSERT INTO actual_employers (enterprise_id, name, credit_code, contact, phone, status, created_at) " +
            "VALUES (#{enterpriseId}, #{name}, #{creditCode}, #{contact}, #{phone}, #{status}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ActualEmployer e);

    @Update("UPDATE actual_employers SET name=#{name}, credit_code=#{creditCode}, contact=#{contact}, phone=#{phone}, status=#{status} WHERE id=#{id}")
    int update(ActualEmployer e);

    @Delete("DELETE FROM actual_employers WHERE id = #{id}")
    int delete(Integer id);

    @Select("SELECT COUNT(*) FROM work_positions WHERE actual_employer_id = #{id}")
    int countPositions(Integer id);
}
