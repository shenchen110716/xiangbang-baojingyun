package com.xbb.baojing.timeliness;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ParticipationOperationMapper {
    String COLUMNS = "id, enterprise_id as enterpriseId, actual_employer_id as actualEmployerId, " +
            "person_id as personId, operation_type as operationType, submitted_by as submittedBy, " +
            "batch_id as batchId, plan_id as planId, rule_snapshot_json as ruleSnapshotJson, " +
            "submitted_at as submittedAt, expected_at as expectedAt, " +
            "insurer_confirmed_at as insurerConfirmedAt, system_sent_at as systemSentAt";

    @Select("SELECT " + COLUMNS + " FROM participation_operations WHERE id = #{id}")
    ParticipationOperation findById(Integer id);

    @Select("SELECT " + COLUMNS + " FROM participation_operations WHERE person_id = #{personId} " +
            "AND operation_type = #{operationType} ORDER BY submitted_at DESC LIMIT 1")
    ParticipationOperation findLatestForPerson(Integer personId, String operationType);
}
