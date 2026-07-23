package com.xbb.baojing.claim;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface ClaimMapper {
    String COLS = "id, enterprise_id as enterpriseId, person_id as personId, policy_id as policyId, claim_no as claimNo, description, status, amount, " +
            "accident_at as accidentAt, accident_place as accidentPlace, accident_type as accidentType, injury_part as injuryPart, payee_type as payeeType, hospital, diagnosis, " +
            "medical_cost as medicalCost, contact_name as contactName, contact_phone as contactPhone, insurer_report_no as insurerReportNo, " +
            "current_handler as currentHandler, deadline, approved_amount as approvedAmount, paid_at as paidAt, " +
            "rejection_reason as rejectionReason, review_note as reviewNote, sla_deadline as slaDeadline, risk_level as riskLevel, created_at as createdAt";

    @Select("<script>SELECT " + COLS + " FROM claims WHERE 1=1 " +
            "<if test='enterpriseId != null'>AND enterprise_id = #{enterpriseId}</if> " +
            "<if test='status != null and status != \"\"'>AND status = #{status}</if> " +
            "ORDER BY id DESC</script>")
    List<Claim> search(@Param("enterpriseId") Integer enterpriseId, @Param("status") String status);

    @Select("SELECT " + COLS + " FROM claims WHERE id = #{id}")
    Claim findById(Integer id);

    @Insert("INSERT INTO claims (enterprise_id, person_id, policy_id, claim_no, description, status, amount, accident_at, accident_place, " +
            "accident_type, injury_part, payee_type, hospital, diagnosis, medical_cost, contact_name, contact_phone, insurer_report_no, current_handler, " +
            "deadline, approved_amount, paid_at, rejection_reason, review_note, sla_deadline, risk_level, created_at) VALUES (" +
            "#{enterpriseId}, #{personId}, #{policyId}, #{claimNo}, #{description}, #{status}, #{amount}, #{accidentAt}, #{accidentPlace}, " +
            "#{accidentType}, #{injuryPart}, #{payeeType}, #{hospital}, #{diagnosis}, #{medicalCost}, #{contactName}, #{contactPhone}, #{insurerReportNo}, " +
            "#{currentHandler}, #{deadline}, #{approvedAmount}, #{paidAt}, #{rejectionReason}, #{reviewNote}, #{slaDeadline}, #{riskLevel}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Claim c);

    @Update("UPDATE claims SET description=#{description}, status=#{status}, amount=#{amount}, hospital=#{hospital}, " +
            "diagnosis=#{diagnosis}, injury_part=#{injuryPart}, payee_type=#{payeeType}, medical_cost=#{medicalCost}, contact_name=#{contactName}, contact_phone=#{contactPhone}, " +
            "insurer_report_no=#{insurerReportNo}, current_handler=#{currentHandler}, deadline=#{deadline}, " +
            "approved_amount=#{approvedAmount}, paid_at=#{paidAt}, rejection_reason=#{rejectionReason}, review_note=#{reviewNote}, " +
            "sla_deadline=#{slaDeadline}, risk_level=#{riskLevel} WHERE id=#{id}")
    int update(Claim c);
}
