package com.xbb.baojing.common;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AuditLogMapper {

    @Insert("INSERT INTO audit_logs (user_id, action, object_type, object_id, detail, created_at) " +
            "VALUES (#{userId}, #{action}, #{objectType}, #{objectId}, #{detail}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AuditLog log);

    @Select("SELECT l.id, l.user_id as userId, u.name as operator, l.action, l.object_type as objectType, " +
            "l.object_id as objectId, l.detail, l.created_at as createdAt " +
            "FROM audit_logs l LEFT JOIN users u ON u.id = l.user_id " +
            "ORDER BY l.id DESC LIMIT #{limit}")
    List<AuditLog> findRecent(@Param("limit") int limit);

    @Select("SELECT l.id, l.user_id as userId, u.name as operator, l.action, l.object_type as objectType, " +
            "l.object_id as objectId, l.detail, l.created_at as createdAt " +
            "FROM audit_logs l LEFT JOIN users u ON u.id = l.user_id " +
            "WHERE l.user_id IN (SELECT id FROM users WHERE enterprise_id = #{enterpriseId}) " +
            "ORDER BY l.id DESC LIMIT #{limit}")
    List<AuditLog> findRecentForEnterprise(@Param("enterpriseId") Integer enterpriseId, @Param("limit") int limit);
}
