package com.xbb.baojing.timeliness;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TimelinessOutboxMapper {
    String COLUMNS = "id, employment_fact_id as employmentFactId, reason, status, attempts, " +
            "last_error as lastError, created_at as createdAt, processed_at as processedAt";

    @Select("SELECT " + COLUMNS + " FROM timeliness_outbox WHERE status IN ('pending','processing') " +
            "ORDER BY id LIMIT #{limit}")
    java.util.List<TimelinessOutbox> findLivePending(int limit);
}
