package com.xbb.baojing.position;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface PositionVideoMapper {
    String COLS = "id, position_id as positionId, name, url, status, review_note as reviewNote, created_at as createdAt";

    @Select("SELECT " + COLS + " FROM position_videos WHERE position_id = #{positionId} ORDER BY id DESC")
    List<PositionVideo> findByPosition(Integer positionId);

    @Select("SELECT " + COLS + " FROM position_videos WHERE position_id = #{positionId} ORDER BY id DESC LIMIT 1")
    PositionVideo findLatestForPosition(Integer positionId);

    @Select("SELECT " + COLS + " FROM position_videos WHERE id = #{id}")
    PositionVideo findById(Integer id);

    @Select("SELECT COUNT(*) FROM position_videos WHERE position_id = #{positionId}")
    int countForPosition(Integer positionId);

    @Insert("INSERT INTO position_videos (position_id, name, url, status, review_note, created_at) " +
            "VALUES (#{positionId}, #{name}, #{url}, #{status}, #{reviewNote}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(PositionVideo v);

    @Update("UPDATE position_videos SET status=#{status}, review_note=#{reviewNote} WHERE id=#{id}")
    int update(PositionVideo v);

    @Delete("DELETE FROM position_videos WHERE id = #{id}")
    int delete(Integer id);
}
