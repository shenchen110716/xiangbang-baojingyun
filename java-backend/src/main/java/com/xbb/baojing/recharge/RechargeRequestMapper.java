package com.xbb.baojing.recharge;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface RechargeRequestMapper {
    String COLS = "id, enterprise_id as enterpriseId, account_type as accountType, insurer, account_id as accountId, " +
            "amount, receipt_file_url as receiptFileUrl, status, reject_reason as rejectReason, " +
            "created_by as createdBy, confirmed_by as confirmedBy, confirmed_at as confirmedAt, created_at as createdAt";

    @Select("<script>SELECT " + COLS + " FROM recharge_requests WHERE 1=1 " +
            "<if test='enterpriseId != null'>AND enterprise_id = #{enterpriseId}</if> " +
            "<if test='status != null and status != \"\"'>AND status = #{status}</if> " +
            "ORDER BY id DESC</script>")
    List<RechargeRequest> search(@Param("enterpriseId") Integer enterpriseId, @Param("status") String status);

    @Select("SELECT " + COLS + " FROM recharge_requests WHERE id = #{id}")
    RechargeRequest findById(Integer id);

    @Insert("INSERT INTO recharge_requests (enterprise_id, account_type, insurer, account_id, amount, receipt_file_url, status, created_by, created_at) " +
            "VALUES (#{enterpriseId}, #{accountType}, #{insurer}, #{accountId}, #{amount}, #{receiptFileUrl}, #{status}, #{createdBy}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(RechargeRequest e);

    @Update("UPDATE recharge_requests SET status=#{status}, reject_reason=#{rejectReason}, confirmed_by=#{confirmedBy}, confirmed_at=#{confirmedAt} WHERE id=#{id}")
    int update(RechargeRequest e);
}
