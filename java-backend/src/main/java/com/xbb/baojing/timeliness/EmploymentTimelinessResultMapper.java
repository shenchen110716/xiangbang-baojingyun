package com.xbb.baojing.timeliness;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface EmploymentTimelinessResultMapper {
    String COLUMNS = "id, employment_fact_id as employmentFactId, " +
            "employment_fact_revision_no as employmentFactRevisionNo, operation_type as operationType, " +
            "enterprise_id as enterpriseId, actual_employer_id as actualEmployerId, person_id as personId, " +
            "responsible_user_id as responsibleUserId, primary_manager_user_id as primaryManagerUserId, " +
            "actual_business_at as actualBusinessAt, expected_coverage_at as expectedCoverageAt, " +
            "actual_coverage_at as actualCoverageAt, timeliness_status as timelinessStatus, " +
            "delay_seconds as delaySeconds, early_seconds as earlySeconds, " +
            "coverage_gap_seconds as coverageGapSeconds, excess_premium as excessPremium, " +
            "early_premium as earlyPremium, feedback_status as feedbackStatus, " +
            "feedback_deadline_at as feedbackDeadlineAt, responsibility_reason as responsibilityReason, " +
            "responsibility_evidence_json as responsibilityEvidenceJson, " +
            "product_rule_version as productRuleVersion, calculation_version as calculationVersion, " +
            "calculated_at as calculatedAt, status";

    // status='current' 是唯一权威判定；'unmatched'/'conflict' 停留在数据质量队列，不进分子分母（§20.6）。
    // enterpriseId 为 null 表示总后台（admin）——镜像 Python _scoped：企业过滤只在 role=='enterprise'
    // 时施加，admin 跨企业读取，不能退化成 `enterprise_id = NULL`（永不命中）。
    @Select("<script>SELECT " + COLUMNS + " FROM employment_timeliness_results " +
            "WHERE status = 'current' " +
            "<if test='enterpriseId != null'>AND enterprise_id = #{enterpriseId}</if> " +
            "<if test='employerIds != null'>AND actual_employer_id IN " +
            "<foreach collection='employerIds' item='eid' open='(' separator=',' close=')'>#{eid}</foreach></if> " +
            "ORDER BY id</script>")
    List<EmploymentTimelinessResult> findCurrentScoped(@Param("enterpriseId") Integer enterpriseId,
                                                        @Param("employerIds") List<Integer> employerIds);
}
