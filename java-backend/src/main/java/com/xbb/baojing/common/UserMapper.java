package com.xbb.baojing.common;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface UserMapper {

    String COLUMNS = "id, username, password_hash as passwordHash, name, role, enterprise_id as enterpriseId, " +
            "phone, status, active, is_owner as owner, enterprise_role as enterpriseRole, session_version as sessionVersion, created_at as createdAt";

    @Select("SELECT " + COLUMNS + " FROM users WHERE id = #{id}")
    User findById(Integer id);

    @Select("SELECT " + COLUMNS + " FROM users WHERE username = #{username}")
    User findByUsername(String username);

    @Select("SELECT " + COLUMNS + " FROM users WHERE role = 'salesperson' ORDER BY id DESC")
    List<User> findAgents();

    @Select("SELECT " + COLUMNS + " FROM users WHERE role = 'enterprise' ORDER BY id DESC")
    List<User> findEnterpriseUsers();

    @Select("<script>SELECT " + COLUMNS + " FROM users WHERE role = 'enterprise' " +
            "<if test='enterpriseId != null'>AND enterprise_id = #{enterpriseId}</if> ORDER BY id DESC</script>")
    List<User> findOperators(Integer enterpriseId);

    @Select("SELECT " + COLUMNS + " FROM users WHERE role = 'enterprise' AND enterprise_id = #{enterpriseId} AND is_owner = true ORDER BY id ASC")
    List<User> findOwners(Integer enterpriseId);

    @Select("SELECT DISTINCT enterprise_id FROM users WHERE role = 'enterprise' AND enterprise_id IS NOT NULL")
    List<Integer> findDistinctEnterpriseIdsWithUsers();

    @Select("SELECT " + COLUMNS + " FROM users WHERE role = 'enterprise' AND is_owner = true AND phone = #{phone} AND id != #{excludeId} AND active = true ORDER BY id ASC")
    List<User> findLinkedOwnersByPhone(@Param("phone") String phone, @Param("excludeId") int excludeId);

    @Insert("INSERT INTO users (username, password_hash, name, role, enterprise_id, phone, status, active, is_owner, enterprise_role, session_version, created_at) " +
            "VALUES (#{username}, #{passwordHash}, #{name}, #{role}, #{enterpriseId}, #{phone}, #{status}, #{active}, #{owner}, #{enterpriseRole}, #{sessionVersion}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(User user);

    @Update("UPDATE users SET username=#{username}, password_hash=#{passwordHash}, name=#{name}, role=#{role}, " +
            "enterprise_id=#{enterpriseId}, phone=#{phone}, status=#{status}, active=#{active}, is_owner=#{owner}, enterprise_role=#{enterpriseRole}, " +
            "session_version=#{sessionVersion} WHERE id=#{id}")
    int update(User user);

    @Select("SELECT COUNT(*) FROM users WHERE username = #{username}")
    int countByUsername(String username);
}
