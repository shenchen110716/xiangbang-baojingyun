package com.xbb.baojing.recharge;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface InsurerAccountMapper {
    String COLS = "id, label, bank_name as bankName, account_no as accountNo, account_holder as accountHolder, " +
            "status, created_at as createdAt";

    @Select("SELECT " + COLS + " FROM insurer_accounts ORDER BY id DESC")
    List<InsurerAccount> findAll();

    @Select("SELECT " + COLS + " FROM insurer_accounts WHERE id = #{id}")
    InsurerAccount findById(Integer id);

    @Select("SELECT " + COLS + " FROM insurer_accounts WHERE label = #{label} LIMIT 1")
    InsurerAccount findByLabel(String label);

    @Insert("INSERT INTO insurer_accounts (label, bank_name, account_no, account_holder, status, created_at) " +
            "VALUES (#{label}, #{bankName}, #{accountNo}, #{accountHolder}, #{status}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(InsurerAccount e);

    @Update("UPDATE insurer_accounts SET label=#{label}, bank_name=#{bankName}, account_no=#{accountNo}, " +
            "account_holder=#{accountHolder}, status=#{status} WHERE id=#{id}")
    int update(InsurerAccount e);
}
