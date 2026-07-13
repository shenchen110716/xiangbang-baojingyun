package com.xbb.baojing.finance;

import org.apache.ibatis.annotations.*;

@Mapper
public interface PaymentRecordMapper {
    String COLS = "id, order_no as orderNo, enterprise_id as enterpriseId, account, amount, status, provider, created_at as createdAt";

    @Select("SELECT " + COLS + " FROM payment_records WHERE order_no = #{orderNo}")
    PaymentRecord findByOrderNo(String orderNo);

    @Insert("INSERT INTO payment_records (order_no, enterprise_id, account, amount, status, provider, created_at) " +
            "VALUES (#{orderNo}, #{enterpriseId}, #{account}, #{amount}, #{status}, #{provider}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(PaymentRecord p);

    @Update("UPDATE payment_records SET status=#{status} WHERE id=#{id}")
    int update(PaymentRecord p);

    @Select("SELECT COUNT(*) FROM payment_records WHERE status = #{status}")
    int countByStatus(String status);
}
