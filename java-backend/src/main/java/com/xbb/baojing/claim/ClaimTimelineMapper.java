package com.xbb.baojing.claim;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ClaimTimelineMapper {
    String COLS = "id, claim_id as claimId, node, action, note, operator, created_at as createdAt";

    @Select("SELECT " + COLS + " FROM claim_timelines WHERE claim_id = #{claimId} ORDER BY id ASC")
    List<ClaimTimeline> findByClaim(Integer claimId);

    @Insert("INSERT INTO claim_timelines (claim_id, node, action, note, operator, created_at) " +
            "VALUES (#{claimId}, #{node}, #{action}, #{note}, #{operator}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ClaimTimeline t);
}
