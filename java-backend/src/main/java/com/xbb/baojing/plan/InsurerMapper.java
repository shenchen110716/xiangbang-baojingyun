package com.xbb.baojing.plan;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface InsurerMapper {
    String COLS = "id, name, contact, phone, status, pending_name as pendingName, pending_contact as pendingContact, " +
            "pending_phone as pendingPhone, pending_submitted_at as pendingSubmittedAt, created_at as createdAt";

    @Select("SELECT " + COLS + " FROM insurers ORDER BY id DESC")
    List<Insurer> findAll();

    @Select("SELECT " + COLS + " FROM insurers WHERE id = #{id}")
    Insurer findById(Integer id);

    @Select("SELECT " + COLS + " FROM insurers WHERE name = #{name} LIMIT 1")
    Insurer findByName(String name);

    @Insert("INSERT INTO insurers (name, contact, phone, status, created_at) VALUES (#{name}, #{contact}, #{phone}, #{status}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Insurer i);

    @Update("UPDATE insurers SET name=#{name}, contact=#{contact}, phone=#{phone}, status=#{status}, " +
            "pending_name=#{pendingName}, pending_contact=#{pendingContact}, pending_phone=#{pendingPhone}, " +
            "pending_submitted_at=#{pendingSubmittedAt} WHERE id=#{id}")
    int update(Insurer i);

    @Delete("DELETE FROM insurers WHERE id = #{id}")
    int delete(Integer id);
}
