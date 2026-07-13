package com.xbb.baojing.claim;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface ClaimDocumentMapper {
    String COLS = "id, claim_id as claimId, name, url, doc_type as docType, status, review_note as reviewNote, created_at as createdAt";

    @Select("SELECT " + COLS + " FROM claim_documents WHERE claim_id = #{claimId} ORDER BY id DESC")
    List<ClaimDocument> findByClaim(Integer claimId);

    @Select("SELECT " + COLS + " FROM claim_documents WHERE id = #{id}")
    ClaimDocument findById(Integer id);

    @Select("SELECT DISTINCT doc_type FROM claim_documents WHERE claim_id = #{claimId} AND status IN ('uploaded','accepted')")
    List<String> findUploadedTypes(Integer claimId);

    @Insert("INSERT INTO claim_documents (claim_id, name, url, doc_type, status, review_note, created_at) " +
            "VALUES (#{claimId}, #{name}, #{url}, #{docType}, #{status}, #{reviewNote}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ClaimDocument d);

    @Update("UPDATE claim_documents SET status=#{status}, review_note=#{reviewNote} WHERE id=#{id}")
    int update(ClaimDocument d);

    @Delete("DELETE FROM claim_documents WHERE id = #{id}")
    int delete(Integer id);
}
