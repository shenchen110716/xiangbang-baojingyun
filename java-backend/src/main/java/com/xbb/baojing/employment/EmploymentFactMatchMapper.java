package com.xbb.baojing.employment;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface EmploymentFactMatchMapper {
    String COLUMNS = "id, employment_fact_id as employmentFactId, match_status as matchStatus, " +
            "match_method as matchMethod, candidate_person_id as candidatePersonId, " +
            "matched_person_id as matchedPersonId, confidence, reason, confirmed_by as confirmedBy, " +
            "confirmed_at as confirmedAt, created_at as createdAt";

    @Select("SELECT " + COLUMNS + " FROM employment_fact_matches WHERE id = #{id}")
    EmploymentFactMatch findById(Integer id);

    @Select("SELECT " + COLUMNS + " FROM employment_fact_matches WHERE employment_fact_id = #{factId} " +
            "ORDER BY id DESC")
    java.util.List<EmploymentFactMatch> findByFact(Integer factId);
}
