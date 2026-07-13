package com.xbb.baojing.insured;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface InsuredPersonMapper {
    String COLS = "id, enterprise_id as enterpriseId, name, phone, id_number as idNumber, occupation, " +
            "occupation_class as occupationClass, position_id as positionId, status, policy_id as policyId, created_at as createdAt";

    @Select("<script>SELECT " + COLS + " FROM insured_people WHERE 1=1 " +
            "<if test='enterpriseId != null'>AND enterprise_id = #{enterpriseId}</if> " +
            "<if test='q != null and q != \"\"'>AND (name LIKE CONCAT('%',#{q},'%') OR phone LIKE CONCAT('%',#{q},'%'))</if> " +
            "ORDER BY id DESC</script>")
    List<InsuredPerson> search(@Param("enterpriseId") Integer enterpriseId, @Param("q") String q);

    @Select("SELECT " + COLS + " FROM insured_people WHERE id = #{id}")
    InsuredPerson findById(Integer id);

    @Select("SELECT " + COLS + " FROM insured_people WHERE policy_id = #{policyId} ORDER BY id ASC")
    List<InsuredPerson> findByPolicy(Integer policyId);

    @Select("SELECT COUNT(*) FROM insured_people WHERE enterprise_id = #{enterpriseId} AND id_number = #{idNumber} AND id != #{excludeId}")
    int countDuplicateIdNumber(@Param("enterpriseId") Integer enterpriseId, @Param("idNumber") String idNumber, @Param("excludeId") int excludeId);

    @Select("SELECT COUNT(*) FROM insured_people WHERE id_number = #{idNumber}")
    int countByIdNumberGlobal(String idNumber);

    @Select("SELECT " + COLS + " FROM insured_people WHERE enterprise_id = #{enterpriseId} AND id_number = #{idNumber}")
    InsuredPerson findByEnterpriseAndIdNumber(@Param("enterpriseId") Integer enterpriseId, @Param("idNumber") String idNumber);

    @Insert("INSERT INTO insured_people (enterprise_id, name, phone, id_number, occupation, occupation_class, position_id, status, policy_id, created_at) " +
            "VALUES (#{enterpriseId}, #{name}, #{phone}, #{idNumber}, #{occupation}, #{occupationClass}, #{positionId}, #{status}, #{policyId}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(InsuredPerson p);

    @Update("UPDATE insured_people SET name=#{name}, phone=#{phone}, id_number=#{idNumber}, occupation=#{occupation}, " +
            "occupation_class=#{occupationClass}, position_id=#{positionId}, status=#{status}, policy_id=#{policyId} WHERE id=#{id}")
    int update(InsuredPerson p);

    @Select("SELECT COUNT(*) FROM insured_people WHERE enterprise_id = #{enterpriseId} AND status IN ('active','pending')")
    int countActiveOrPendingForEnterprise(Integer enterpriseId);
}
