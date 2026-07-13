package com.xbb.baojing.enrollment;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface EnrollmentEmailMapper {
    String COLS = "id, enterprise_id as enterpriseId, plan_id as planId, kind, recipient, filename, " +
            "people_count as peopleCount, request_id as requestId, status, created_at as createdAt";

    @Select("<script>SELECT " + COLS + " FROM enrollment_emails WHERE 1=1 " +
            "<if test='enterpriseId != null'>AND enterprise_id = #{enterpriseId}</if> ORDER BY id DESC</script>")
    List<EnrollmentEmail> search(Integer enterpriseId);

    @Insert("INSERT INTO enrollment_emails (enterprise_id, plan_id, kind, recipient, filename, people_count, request_id, status, created_at) " +
            "VALUES (#{enterpriseId}, #{planId}, #{kind}, #{recipient}, #{filename}, #{peopleCount}, #{requestId}, #{status}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(EnrollmentEmail e);
}
