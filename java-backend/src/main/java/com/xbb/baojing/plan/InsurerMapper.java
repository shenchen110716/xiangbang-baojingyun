package com.xbb.baojing.plan;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface InsurerMapper {
    String COLS = "id, name, contact, phone, credit_code as creditCode, email, address, status, " +
            "pending_name as pendingName, pending_contact as pendingContact, pending_phone as pendingPhone, " +
            "pending_credit_code as pendingCreditCode, pending_email as pendingEmail, pending_address as pendingAddress, " +
            "pending_submitted_at as pendingSubmittedAt, created_at as createdAt";

    @Select("SELECT " + COLS + " FROM insurers ORDER BY id DESC")
    List<Insurer> findAll();

    @Select("SELECT " + COLS + " FROM insurers WHERE id = #{id}")
    Insurer findById(Integer id);

    @Select("SELECT " + COLS + " FROM insurers WHERE name = #{name} LIMIT 1")
    Insurer findByName(String name);

    @Insert("INSERT INTO insurers (name, contact, phone, credit_code, email, address, status, created_at) " +
            "VALUES (#{name}, #{contact}, #{phone}, #{creditCode}, #{email}, #{address}, #{status}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Insurer i);

    @Update("UPDATE insurers SET name=#{name}, contact=#{contact}, phone=#{phone}, credit_code=#{creditCode}, email=#{email}, address=#{address}, status=#{status}, " +
            "pending_name=#{pendingName}, pending_contact=#{pendingContact}, pending_phone=#{pendingPhone}, " +
            "pending_credit_code=#{pendingCreditCode}, pending_email=#{pendingEmail}, pending_address=#{pendingAddress}, " +
            "pending_submitted_at=#{pendingSubmittedAt} WHERE id=#{id}")
    int update(Insurer i);

    @Delete("DELETE FROM insurers WHERE id = #{id}")
    int delete(Integer id);
}
