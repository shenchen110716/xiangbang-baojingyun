package com.xbb.baojing.finance;

import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface LedgerMapper {
    String COLS = "l.id, l.enterprise_id as enterpriseId, l.account, l.direction, l.amount, l.business_type as businessType, " +
            "l.business_id as businessId, l.idempotency_key as idempotencyKey, l.created_by as createdBy, l.occurred_at as occurredAt, " +
            "u.name as operator";

    @Select("SELECT " + COLS + " FROM ledger_entries l LEFT JOIN users u ON u.id = l.created_by " +
            "WHERE l.enterprise_id = #{enterpriseId} ORDER BY l.id DESC")
    List<LedgerEntry> findByEnterprise(Integer enterpriseId);

    @Insert("INSERT INTO ledger_entries (enterprise_id, account, direction, amount, business_type, business_id, idempotency_key, created_by, occurred_at) " +
            "VALUES (#{enterpriseId}, #{account}, #{direction}, #{amount}, #{businessType}, #{businessId}, #{idempotencyKey}, #{createdBy}, #{occurredAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(LedgerEntry e);

    @Select("SELECT COALESCE(SUM(amount), 0) FROM ledger_entries WHERE enterprise_id = #{enterpriseId} AND account = #{account} AND direction = 'credit'")
    BigDecimal sumCredit(@Param("enterpriseId") Integer enterpriseId, @Param("account") String account);

    @Select("SELECT COALESCE(SUM(amount), 0) FROM ledger_entries WHERE enterprise_id = #{enterpriseId} AND account = #{account} AND direction = 'debit'")
    BigDecimal sumDebit(@Param("enterpriseId") Integer enterpriseId, @Param("account") String account);
}
