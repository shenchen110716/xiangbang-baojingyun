package com.xbb.baojing.plan;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbb.baojing.common.ApiException;
import com.xbb.baojing.common.AuditService;
import com.xbb.baojing.common.InternalPricingFilter;
import com.xbb.baojing.common.Rbac;
import com.xbb.baojing.common.User;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class PlanController {
    private final InsurancePlanMapper planMapper;
    private final PlanTierMapper tierMapper;
    private final PricingService pricingService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public PlanController(InsurancePlanMapper planMapper, PlanTierMapper tierMapper, PricingService pricingService, AuditService auditService, ObjectMapper objectMapper) {
        this.planMapper = planMapper;
        this.tierMapper = tierMapper;
        this.pricingService = pricingService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    public record PlanIn(String insurer, String insurerEmail, String name, String coverage, String occupationClasses,
                          double price, double commissionRate, double profitAmount, String paymentMode,
                          String billingMode, String effectiveMode) {}
    public record PlanUpdate(String insurer, String insurerEmail, String name, String coverage, String occupationClasses,
                              Double price, Double commissionRate, Double profitAmount, String paymentMode,
                              String billingMode, String effectiveMode) {}
    public record PlanTierIn(int planId, String occupationClass, double price, String coverage) {}

    public static class PlanOut extends InsurancePlan {
        private PricingSnapshot pricing;
        @JsonUnwrapped
        public PricingSnapshot getPricing() { return pricing; }
        public void setPricing(PricingSnapshot v) { this.pricing = v; }
    }

    private PlanOut toOut(InsurancePlan p) {
        PlanOut out = new PlanOut();
        out.setId(p.getId());
        out.setInsurer(p.getInsurer());
        out.setInsurerEmail(p.getInsurerEmail());
        out.setName(p.getName());
        out.setCoverage(p.getCoverage());
        out.setOccupationClasses(p.getOccupationClasses());
        out.setPrice(p.getPrice());
        out.setCommissionRate(p.getCommissionRate());
        out.setProfitAmount(p.getProfitAmount());
        out.setPaymentMode(p.getPaymentMode());
        out.setBillingMode(p.getBillingMode());
        out.setEffectiveMode(p.getEffectiveMode());
        out.setStatus(p.getStatus());
        out.setCreatedAt(p.getCreatedAt());
        out.setPricing(pricingService.snapshot(p));
        return out;
    }

    @GetMapping("/plans")
    public List<Object> list(User user) {
        List<InsurancePlan> plans = "enterprise".equals(user.getRole()) && user.getEnterpriseId() != null
                ? planMapper.findVisibleForEnterprise(user.getEnterpriseId())
                : planMapper.findAll();
        return plans.stream().map(p -> InternalPricingFilter.strip(toOut(p), user, objectMapper)).toList();
    }

    @PostMapping("/plans")
    public PlanOut create(@RequestBody PlanIn data, User user) {
        // NOTE: role check stays inline (not a Rbac.requireRole precondition
        // gate elsewhere) to mirror backend/routers/plans.py's own comment —
        // tests call this directly and assert on the 403 it raises.
        if (!"admin".equals(user.getRole())) throw ApiException.forbidden("仅总后台可新增保险方案");
        InsurancePlan p = new InsurancePlan();
        p.setInsurer(data.insurer());
        p.setInsurerEmail(data.insurerEmail() == null ? "" : data.insurerEmail());
        p.setName(data.name());
        p.setCoverage(data.coverage() == null ? "" : data.coverage());
        p.setOccupationClasses(data.occupationClasses() == null ? "1-4类" : data.occupationClasses());
        p.setPrice(data.price());
        p.setCommissionRate(data.commissionRate());
        p.setProfitAmount(data.profitAmount());
        p.setPaymentMode(data.paymentMode() == null ? "企业直投" : data.paymentMode());
        String effectiveMode = data.effectiveMode() == null ? "next_day" : data.effectiveMode();
        p.setEffectiveMode(effectiveMode);
        p.setBillingMode("immediate".equals(effectiveMode) ? "daily" : (data.billingMode() == null ? "monthly" : data.billingMode()));
        p.setStatus("active");
        p.setCreatedAt(LocalDateTime.now());
        planMapper.insert(p);
        auditService.log(user, "create", "plan", String.valueOf(p.getId()));
        return toOut(p);
    }

    @GetMapping("/plan-tiers")
    public List<PlanTier> tiers(@RequestParam(name = "plan_id", required = false) Integer planId, User user) {
        return tierMapper.search(planId);
    }

    @PostMapping("/plan-tiers")
    public PlanTier addTier(@RequestBody PlanTierIn data, User user) {
        Rbac.requireRole(user, "仅总后台可维护类别价格", "admin");
        if (planMapper.findById(data.planId()) == null) throw ApiException.notFound("保险方案不存在");
        PlanTier t = new PlanTier();
        t.setPlanId(data.planId());
        t.setOccupationClass(data.occupationClass());
        t.setPrice(data.price());
        t.setCoverage(data.coverage() == null ? "" : data.coverage());
        t.setCreatedAt(LocalDateTime.now());
        tierMapper.insert(t);
        auditService.log(user, "create", "plan_tier", String.valueOf(t.getId()));
        return t;
    }

    @PatchMapping("/plans/{id}/status")
    public InsurancePlan setStatus(@PathVariable int id, @RequestParam("status") String status, User user) {
        Rbac.requireRole(user, "仅总后台可维护保险方案", "admin");
        InsurancePlan p = planMapper.findById(id);
        if (p == null) throw ApiException.notFound("方案不存在");
        if (!java.util.Set.of("active", "paused").contains(status)) throw ApiException.badRequest("状态不合法");
        p.setStatus(status);
        planMapper.update(p);
        auditService.log(user, "status_change", "plan", String.valueOf(id), status);
        return p;
    }

    @PatchMapping("/plans/{id}")
    public PlanOut update(@PathVariable int id, @RequestBody PlanUpdate data, User user) {
        Rbac.requireRole(user, "仅总后台可维护保险方案", "admin");
        InsurancePlan p = planMapper.findById(id);
        if (p == null) throw ApiException.notFound("方案不存在");
        if (data.insurer() != null) p.setInsurer(data.insurer());
        if (data.insurerEmail() != null) p.setInsurerEmail(data.insurerEmail());
        if (data.name() != null) p.setName(data.name());
        if (data.coverage() != null) p.setCoverage(data.coverage());
        if (data.occupationClasses() != null) p.setOccupationClasses(data.occupationClasses());
        if (data.price() != null) p.setPrice(data.price());
        if (data.commissionRate() != null) p.setCommissionRate(data.commissionRate());
        if (data.profitAmount() != null) p.setProfitAmount(data.profitAmount());
        if (data.paymentMode() != null) p.setPaymentMode(data.paymentMode());
        if (data.effectiveMode() != null) p.setEffectiveMode(data.effectiveMode());
        if ("immediate".equals(p.getEffectiveMode())) p.setBillingMode("daily");
        else if (data.billingMode() != null) p.setBillingMode(data.billingMode());
        planMapper.update(p);
        auditService.log(user, "update", "plan", String.valueOf(id));
        return toOut(p);
    }

    @DeleteMapping("/plans/{id}")
    public Map<String, Object> delete(@PathVariable int id, User user) {
        Rbac.requireRole(user, "仅总后台可删除保险方案", "admin");
        InsurancePlan p = planMapper.findById(id);
        if (p == null) throw ApiException.notFound("方案不存在");
        if (planMapper.countPolicies(id) > 0) throw ApiException.conflict("该方案已有参保人员或保单使用，不能删除；请先暂停方案");
        planMapper.delete(id);
        auditService.log(user, "delete", "plan", String.valueOf(id));
        return Map.of("ok", true, "deleted_id", id);
    }
}
