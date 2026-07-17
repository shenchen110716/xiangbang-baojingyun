package com.xbb.baojing.employment;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface EmploymentFeedbackBatchMapper {
    String COLUMNS = "id, enterprise_id as enterpriseId, actual_employer_id as actualEmployerId, " +
            "source_type as sourceType, source_filename as sourceFilename, source_file_hash as sourceFileHash, " +
            "source_file_path as sourceFilePath, reported_at as reportedAt, imported_by as importedBy, " +
            "imported_at as importedAt, total_rows as totalRows, valid_rows as validRows, " +
            "invalid_rows as invalidRows, status, preview_version as previewVersion, " +
            "confirm_token_digest as confirmTokenDigest, created_at as createdAt, updated_at as updatedAt";

    @Select("SELECT " + COLUMNS + " FROM employment_feedback_batches WHERE id = #{id}")
    EmploymentFeedbackBatch findById(Integer id);

    @Select("SELECT " + COLUMNS + " FROM employment_feedback_batches WHERE enterprise_id = #{enterpriseId} " +
            "ORDER BY id DESC")
    List<EmploymentFeedbackBatch> findByEnterprise(@Param("enterpriseId") Integer enterpriseId);
}
