package com.xbb.baojing.recharge;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface EnterprisePremiumAccountMapper {
    String COLS = "id, enterprise_id as enterpriseId, account_id as accountId, balance";

    @Select("SELECT " + COLS + " FROM enterprise_premium_accounts WHERE enterprise_id = #{enterpriseId}")
    List<EnterprisePremiumAccount> findByEnterprise(Integer enterpriseId);

    @Select("SELECT " + COLS + " FROM enterprise_premium_accounts WHERE enterprise_id = #{enterpriseId} AND account_id = #{accountId} LIMIT 1")
    EnterprisePremiumAccount findByEnterpriseAndAccount(@Param("enterpriseId") Integer enterpriseId, @Param("accountId") Integer accountId);

    @Insert("INSERT INTO enterprise_premium_accounts (enterprise_id, account_id, balance) VALUES (#{enterpriseId}, #{accountId}, #{balance})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(EnterprisePremiumAccount e);

    @Update("UPDATE enterprise_premium_accounts SET balance = #{balance} WHERE id = #{id}")
    int update(EnterprisePremiumAccount e);
}
