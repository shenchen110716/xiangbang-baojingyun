package com.xbb.baojing.finance;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface InvoiceMapper {
    String COLS = "id, enterprise_id as enterpriseId, account, amount, title, tax_no as taxNo, email, status, created_at as createdAt";

    @Select("<script>SELECT " + COLS + " FROM invoices WHERE 1=1 " +
            "<if test='enterpriseId != null'>AND enterprise_id = #{enterpriseId}</if> ORDER BY id DESC</script>")
    List<Invoice> search(Integer enterpriseId);

    @Select("SELECT " + COLS + " FROM invoices WHERE id = #{id}")
    Invoice findById(Integer id);

    @Insert("INSERT INTO invoices (enterprise_id, account, amount, title, tax_no, email, status, created_at) " +
            "VALUES (#{enterpriseId}, #{account}, #{amount}, #{title}, #{taxNo}, #{email}, #{status}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Invoice i);

    @Update("UPDATE invoices SET status=#{status} WHERE id=#{id}")
    int update(Invoice i);
}
