package com.xbb.baojing.recharge;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface InsurerAccountLinkMapper {
    String COLS = "id, insurer, account_id as accountId, created_at as createdAt";

    @Select("SELECT " + COLS + " FROM insurer_account_links ORDER BY id DESC")
    List<InsurerAccountLink> findAll();

    @Select("SELECT " + COLS + " FROM insurer_account_links WHERE id = #{id}")
    InsurerAccountLink findById(Integer id);

    @Select("SELECT " + COLS + " FROM insurer_account_links WHERE insurer = #{insurer} LIMIT 1")
    InsurerAccountLink findByInsurer(String insurer);

    @Select("SELECT insurer FROM insurer_account_links WHERE account_id = #{accountId}")
    List<String> findInsurersByAccount(Integer accountId);

    @Insert("INSERT INTO insurer_account_links (insurer, account_id, created_at) VALUES (#{insurer}, #{accountId}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(InsurerAccountLink e);

    @Delete("DELETE FROM insurer_account_links WHERE id = #{id}")
    int delete(Integer id);
}
