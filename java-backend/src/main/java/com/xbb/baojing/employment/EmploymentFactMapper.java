package com.xbb.baojing.employment;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface EmploymentFactMapper {
    String COLUMNS = "id, enterprise_id as enterpriseId, actual_employer_id as actualEmployerId, " +
            "person_id as personId, external_employee_no as externalEmployeeNo, " +
            "external_employment_id as externalEmploymentId, id_number_hash as idNumberHash, " +
            "id_number_cipher as idNumberCipher, person_name as personName, actual_hire_at as actualHireAt, " +
            "actual_leave_at as actualLeaveAt, feedback_reported_at as feedbackReportedAt, batch_id as batchId, " +
            "source_event_id as sourceEventId, revision_no as revisionNo, " +
            "previous_version_id as previousVersionId, status, created_by as createdBy, created_at as createdAt";

    @Select("SELECT " + COLUMNS + " FROM employment_facts WHERE id = #{id}")
    EmploymentFact findById(Integer id);

    // §20.6: 只有 status='active' 的记录才是权威的、可进入正式指标的事实。
    // enterpriseId 为 null 表示总后台（admin）——镜像 Python active_facts：企业过滤只在
    // role=='enterprise' 时施加，admin 跨企业读取，不能退化成 `enterprise_id = NULL`（永不命中）。
    @Select("<script>SELECT " + COLUMNS + " FROM employment_facts WHERE status = 'active' " +
            "<if test='enterpriseId != null'>AND enterprise_id = #{enterpriseId}</if> " +
            "<if test='employerIds != null'>AND actual_employer_id IN " +
            "<foreach collection='employerIds' item='eid' open='(' separator=',' close=')'>#{eid}</foreach></if> " +
            "ORDER BY id</script>")
    List<EmploymentFact> findActiveScoped(@Param("enterpriseId") Integer enterpriseId,
                                          @Param("employerIds") List<Integer> employerIds);
}
