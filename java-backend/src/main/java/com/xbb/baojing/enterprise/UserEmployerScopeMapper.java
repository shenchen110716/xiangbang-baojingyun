package com.xbb.baojing.enterprise;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface UserEmployerScopeMapper {
    String COLS = "id, user_id as userId, enterprise_id as enterpriseId, actual_employer_id as actualEmployerId, " +
            "responsibility_type as responsibilityType, granted_by as grantedBy, assigned_at as assignedAt, " +
            "revoked_at as revokedAt, status, created_at as createdAt";

    @Select("SELECT actual_employer_id FROM user_employer_scopes WHERE user_id = #{userId} " +
            "AND enterprise_id = #{enterpriseId} AND status = 'active' AND revoked_at IS NULL")
    List<Integer> findActiveEmployerIds(@Param("userId") Integer userId, @Param("enterpriseId") Integer enterpriseId);

    @Select("SELECT " + COLS + " FROM user_employer_scopes WHERE actual_employer_id = #{actualEmployerId} " +
            "AND responsibility_type = 'primary' AND status = 'active' AND assigned_at <= #{occurredAt} " +
            "AND (revoked_at IS NULL OR revoked_at > #{occurredAt}) ORDER BY assigned_at DESC LIMIT 1")
    UserEmployerScope findPrimaryManagerAt(@Param("actualEmployerId") Integer actualEmployerId,
                                           @Param("occurredAt") LocalDateTime occurredAt);
}
