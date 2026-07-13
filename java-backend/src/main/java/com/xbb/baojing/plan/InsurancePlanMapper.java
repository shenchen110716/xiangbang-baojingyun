package com.xbb.baojing.plan;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface InsurancePlanMapper {
    String COLS = "id, insurer, insurer_email as insurerEmail, name, coverage, occupation_classes as occupationClasses, " +
            "price, commission_rate as commissionRate, profit_amount as profitAmount, payment_mode as paymentMode, " +
            "billing_mode as billingMode, effective_mode as effectiveMode, status, created_at as createdAt";

    @Select("SELECT " + COLS + " FROM insurance_plans ORDER BY id DESC")
    List<InsurancePlan> findAll();

    @Select("<script>SELECT DISTINCT p." + "id, p.insurer, p.insurer_email as insurerEmail, p.name, p.coverage, p.occupation_classes as occupationClasses, " +
            "p.price, p.commission_rate as commissionRate, p.profit_amount as profitAmount, p.payment_mode as paymentMode, " +
            "p.billing_mode as billingMode, p.effective_mode as effectiveMode, p.status, p.created_at as createdAt " +
            "FROM insurance_plans p WHERE p.id IN (" +
            "  SELECT plan_id FROM agent_commissions WHERE enterprise_id = #{enterpriseId} " +
            "  UNION " +
            "  SELECT plan_id FROM work_positions WHERE enterprise_id = #{enterpriseId} AND plan_id IS NOT NULL" +
            ") ORDER BY p.id DESC</script>")
    List<InsurancePlan> findVisibleForEnterprise(Integer enterpriseId);

    @Select("SELECT " + COLS + " FROM insurance_plans WHERE id = #{id}")
    InsurancePlan findById(Integer id);

    @Insert("INSERT INTO insurance_plans (insurer, insurer_email, name, coverage, occupation_classes, price, commission_rate, profit_amount, payment_mode, billing_mode, effective_mode, status, created_at) " +
            "VALUES (#{insurer}, #{insurerEmail}, #{name}, #{coverage}, #{occupationClasses}, #{price}, #{commissionRate}, #{profitAmount}, #{paymentMode}, #{billingMode}, #{effectiveMode}, #{status}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(InsurancePlan p);

    @Update("UPDATE insurance_plans SET insurer=#{insurer}, insurer_email=#{insurerEmail}, name=#{name}, coverage=#{coverage}, " +
            "occupation_classes=#{occupationClasses}, price=#{price}, commission_rate=#{commissionRate}, profit_amount=#{profitAmount}, " +
            "payment_mode=#{paymentMode}, billing_mode=#{billingMode}, effective_mode=#{effectiveMode}, status=#{status} WHERE id=#{id}")
    int update(InsurancePlan p);

    @Delete("DELETE FROM insurance_plans WHERE id = #{id}")
    int delete(Integer id);

    @Select("SELECT COUNT(*) FROM policies WHERE plan_id = #{id}")
    int countPolicies(Integer id);
}
