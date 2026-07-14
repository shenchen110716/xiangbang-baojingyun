package com.xbb.baojing.insured;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface PolicyMemberMapper {
    String COLS = "id, policy_id as policyId, person_id as personId, rate_snapshot_json as rateSnapshotJson, " +
            "effective_at as effectiveAt, terminated_at as terminatedAt, endorsement_no as endorsementNo, status, created_at as createdAt";

    @Select("SELECT " + COLS + " FROM policy_members WHERE person_id = #{personId} ORDER BY id DESC")
    List<PolicyMember> findByPerson(Integer personId);

    @Select("SELECT " + COLS + " FROM policy_members ORDER BY effective_at ASC, id ASC")
    List<PolicyMember> findAll();

    @Select("SELECT " + COLS + " FROM policy_members WHERE policy_id = #{policyId} ORDER BY id ASC")
    List<PolicyMember> findByPolicy(Integer policyId);

    @Select("SELECT " + COLS + " FROM policy_members WHERE person_id = #{personId} AND status = 'active' AND terminated_at IS NULL ORDER BY id DESC LIMIT 1")
    PolicyMember findOpenForPerson(Integer personId);

    @Select("SELECT " + COLS + " FROM policy_members WHERE person_id = #{personId} ORDER BY id DESC LIMIT 1")
    PolicyMember findLatestForPerson(Integer personId);

    @Insert("INSERT INTO policy_members (policy_id, person_id, rate_snapshot_json, effective_at, terminated_at, endorsement_no, status, created_at) " +
            "VALUES (#{policyId}, #{personId}, #{rateSnapshotJson}, #{effectiveAt}, #{terminatedAt}, #{endorsementNo}, #{status}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(PolicyMember m);

    @Update("UPDATE policy_members SET terminated_at=#{terminatedAt}, status=#{status} WHERE id=#{id}")
    int update(PolicyMember m);

    @Update("UPDATE policy_members SET effective_at=#{effectiveAt}, terminated_at=#{terminatedAt}, status=#{status} WHERE id=#{id}")
    int updateDates(PolicyMember m);
}
