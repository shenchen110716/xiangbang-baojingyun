package com.xbb.baojing.agent;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.xbb.baojing.common.*;
import com.xbb.baojing.enterprise.Enterprise;
import com.xbb.baojing.enterprise.EnterpriseMapper;
import com.xbb.baojing.insured.InsuredPersonMapper;
import com.xbb.baojing.plan.InsurancePlan;
import com.xbb.baojing.plan.InsurancePlanMapper;
import com.xbb.baojing.plan.PricingService;
import com.xbb.baojing.plan.PricingSnapshot;
import com.xbb.baojing.position.WorkPositionMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api")
public class AgentController {
    private final UserMapper userMapper;
    private final AgentCommissionMapper commissionMapper;
    private final EnterpriseMapper enterpriseMapper;
    private final InsurancePlanMapper planMapper;
    private final InsuredPersonMapper personMapper;
    private final WorkPositionMapper positionMapper;
    private final PricingService pricingService;
    private final AuditService auditService;
    private final PasswordEncoder passwordEncoder;

    public AgentController(UserMapper userMapper, AgentCommissionMapper commissionMapper, EnterpriseMapper enterpriseMapper,
                            InsurancePlanMapper planMapper, InsuredPersonMapper personMapper, WorkPositionMapper positionMapper,
                            PricingService pricingService, AuditService auditService, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.commissionMapper = commissionMapper;
        this.enterpriseMapper = enterpriseMapper;
        this.planMapper = planMapper;
        this.personMapper = personMapper;
        this.positionMapper = positionMapper;
        this.pricingService = pricingService;
        this.auditService = auditService;
        this.passwordEncoder = passwordEncoder;
    }

    public record CommissionIn(int agentId, int enterpriseId, int planId, double rate, String mode, double markupAmount, double salePrice) {}
    public record CommissionUpdate(Double rate, String mode, Double markupAmount, Double salePrice, String status) {}

    public static class CommissionOut extends AgentCommission {
        private String agentName, enterpriseName, planName, insurer;
        private PricingSnapshot pricing;
        public String getAgentName() { return agentName; }
        public void setAgentName(String v) { this.agentName = v; }
        public String getEnterpriseName() { return enterpriseName; }
        public void setEnterpriseName(String v) { this.enterpriseName = v; }
        public String getPlanName() { return planName; }
        public void setPlanName(String v) { this.planName = v; }
        public String getInsurer() { return insurer; }
        public void setInsurer(String v) { this.insurer = v; }
        @JsonUnwrapped
        public PricingSnapshot getPricing() { return pricing; }
        public void setPricing(PricingSnapshot v) { this.pricing = v; }
    }

    private CommissionOut commissionDict(AgentCommission item) {
        User agent = userMapper.findById(item.getAgentId());
        Enterprise enterprise = enterpriseMapper.findById(item.getEnterpriseId());
        InsurancePlan plan = planMapper.findById(item.getPlanId());
        CommissionOut out = new CommissionOut();
        out.setId(item.getId());
        out.setAgentId(item.getAgentId());
        out.setEnterpriseId(item.getEnterpriseId());
        out.setPlanId(item.getPlanId());
        out.setRate(item.getRate());
        out.setMode(Set.of("price", "markup").contains(item.getMode()) ? "price" : "rebate");
        out.setMarkupAmount(item.getMarkupAmount());
        out.setSalePrice(item.getSalePrice());
        out.setStatus(item.getStatus());
        out.setCreatedAt(item.getCreatedAt());
        out.setAgentName(agent != null ? agent.getName() : "");
        out.setEnterpriseName(enterprise != null ? enterprise.getName() : "");
        out.setPlanName(plan != null ? plan.getName() : "");
        out.setInsurer(plan != null ? plan.getInsurer() : "");
        if (plan != null) out.setPricing(pricingService.snapshot(plan, item));
        return out;
    }

    public record AgentOut(int id, String username, String name, String phone, String role, boolean active, String status,
                            java.time.LocalDateTime createdAt, long enterpriseCount, long productCount, long insuredCount, double totalCommission) {}

    public record CommissionRow(AgentCommission commission, String mode, String agentName, String enterpriseName, String planName,
                                 String insurer, long insuredCount, double agentCommissionUnit, double agentCommissionTotal) {}

    private List<Map<String, Object>> agentCommissionRows(int agentId) {
        User agent = userMapper.findById(agentId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AgentCommission rel : commissionMapper.findByAgent(agentId)) {
            InsurancePlan plan = planMapper.findById(rel.getPlanId());
            Enterprise enterprise = enterpriseMapper.findById(rel.getEnterpriseId());
            if (plan == null || enterprise == null) continue;
            long insuredCount = personMapper.search(rel.getEnterpriseId(), null).stream()
                    .filter(p -> !"stopped".equals(p.getStatus()))
                    .filter(p -> p.getPositionId() != null && positionMapper.findById(p.getPositionId()) != null
                            && Objects.equals(positionMapper.findById(p.getPositionId()).getPlanId(), rel.getPlanId()))
                    .count();
            PricingSnapshot unit = pricingService.snapshot(plan, rel);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rel.getId());
            row.put("agent_id", rel.getAgentId());
            row.put("enterprise_id", rel.getEnterpriseId());
            row.put("plan_id", rel.getPlanId());
            row.put("rate", rel.getRate());
            row.put("mode", unit.getCommissionMode());
            row.put("status", rel.getStatus());
            row.put("agent_name", agent != null ? agent.getName() : "");
            row.put("enterprise_name", enterprise.getName());
            row.put("plan_name", plan.getName());
            row.put("insurer", plan.getInsurer());
            row.put("insured_count", insuredCount);
            row.put("agent_commission_unit", unit.getAgentCommissionAmount());
            row.put("agent_commission_total", PricingService.amount(unit.getAgentCommissionAmount() * insuredCount));
            rows.add(row);
        }
        return rows;
    }

    @GetMapping("/agents")
    public List<AgentOut> list(User user) {
        Rbac.requireRole(user, "仅总后台可管理业务员", "admin");
        List<AgentOut> result = new ArrayList<>();
        for (User u : userMapper.findAgents()) {
            List<Map<String, Object>> rows = agentCommissionRows(u.getId());
            List<Map<String, Object>> active = rows.stream().filter(r -> "active".equals(r.get("status"))).toList();
            long enterpriseCount = active.stream().map(r -> r.get("enterprise_id")).distinct().count();
            long insuredCount = active.stream().mapToLong(r -> (Long) r.get("insured_count")).sum();
            double totalCommission = active.stream().mapToDouble(r -> (Double) r.get("agent_commission_total")).sum();
            result.add(new AgentOut(u.getId(), u.getUsername(), u.getName(), u.getPhone(), u.getRole(), u.isActive(), u.getStatus(),
                    u.getCreatedAt(), enterpriseCount, active.size(), insuredCount, PricingService.amount(totalCommission)));
        }
        return result;
    }

    @GetMapping("/agents/{id}/commissions")
    public List<Map<String, Object>> agentCommissions(@PathVariable int id, User user) {
        Rbac.requireRole(user, "仅总后台可查看业务员佣金", "admin");
        if (userMapper.findById(id) == null) throw ApiException.notFound("业务员不存在");
        return agentCommissionRows(id);
    }

    @PostMapping("/agents")
    public Map<String, Object> create(@RequestBody CommonDtos.AgentIn data, User user) {
        Rbac.requireRole(user, "仅总后台可管理业务员", "admin");
        if (userMapper.countByUsername(data.username()) > 0) throw ApiException.conflict("业务员账号已存在");
        User item = new User();
        item.setUsername(data.username());
        item.setPasswordHash(passwordEncoder.encode(data.password()));
        item.setName(data.name());
        item.setPhone(data.phone() == null ? "" : data.phone());
        item.setRole("salesperson");
        item.setCreatedAt(LocalDateTime.now());
        userMapper.insert(item);
        auditService.log(user, "create", "salesperson", String.valueOf(item.getId()));
        return Map.of("id", item.getId(), "username", item.getUsername(), "name", item.getName(), "role", item.getRole(), "active", item.isActive());
    }

    @PatchMapping("/agents/{id}/status")
    public Map<String, Object> setStatus(@PathVariable int id, @RequestParam("status") String status, User user) {
        Rbac.requireRole(user, "仅总后台可管理业务员", "admin");
        User item = userMapper.findById(id);
        if (item == null || !"salesperson".equals(item.getRole())) throw ApiException.notFound("业务员不存在");
        item.setStatus(status);
        item.setActive("active".equals(status));
        userMapper.update(item);
        auditService.log(user, "status_change", "salesperson", String.valueOf(id), status);
        return Map.of("ok", true, "status", item.getStatus());
    }

    @GetMapping("/agent-commissions")
    public List<CommissionOut> allCommissions(User user) {
        Rbac.requireRole(user, "仅总后台可查看业务员佣金", "admin");
        return commissionMapper.findAll().stream().map(this::commissionDict).toList();
    }

    @PostMapping("/agent-commissions")
    public CommissionOut createCommission(@RequestBody CommissionIn data, User user) {
        Rbac.requireRole(user, "仅总后台可配置佣金", "admin");
        User agent = userMapper.findById(data.agentId());
        Enterprise enterprise = enterpriseMapper.findById(data.enterpriseId());
        InsurancePlan plan = planMapper.findById(data.planId());
        if (agent == null || !"salesperson".equals(agent.getRole())) throw ApiException.notFound("业务员不存在");
        if (enterprise == null || plan == null) throw ApiException.notFound("投保单位或产品方案不存在");
        if (enterprise.getAgentId() != null && !enterprise.getAgentId().equals(data.agentId())) throw ApiException.conflict("一个投保单位只能关联一个业务员；该单位已关联其他业务员");
        var resolved = pricingService.validateCommissionPrice(data.mode(), data.rate(), data.salePrice(), data.markupAmount(), plan);
        AgentCommission item = new AgentCommission();
        item.setAgentId(data.agentId());
        item.setEnterpriseId(data.enterpriseId());
        item.setPlanId(data.planId());
        item.setMode(resolved.mode());
        item.setSalePrice(resolved.salePrice());
        item.setRate("price".equals(resolved.mode()) ? 0 : data.rate());
        item.setMarkupAmount("price".equals(resolved.mode()) ? Math.max(0, resolved.salePrice() - pricingService.snapshot(plan).getMinimumSalePrice()) : 0);
        item.setCreatedAt(LocalDateTime.now());
        commissionMapper.insert(item);
        if (enterprise.getAgentId() == null) {
            enterprise.setAgentId(data.agentId());
            enterpriseMapper.update(enterprise);
        }
        auditService.log(user, "create", "agent_commission", String.valueOf(item.getId()));
        return commissionDict(item);
    }

    @PatchMapping("/agent-commissions/{id}")
    public CommissionOut updateCommission(@PathVariable int id, @RequestBody CommissionUpdate data, User user) {
        Rbac.requireRole(user, "仅总后台可修改佣金关系", "admin");
        AgentCommission item = commissionMapper.findById(id);
        if (item == null) throw ApiException.notFound("佣金关系不存在");
        if (data.rate() != null) item.setRate(data.rate());
        if (data.mode() != null) item.setMode(data.mode());
        if (data.markupAmount() != null) item.setMarkupAmount(data.markupAmount());
        if (data.salePrice() != null) item.setSalePrice(data.salePrice());
        if (data.status() != null) item.setStatus(data.status());
        InsurancePlan plan = planMapper.findById(item.getPlanId());
        var resolved = pricingService.validateCommissionPrice(item.getMode(), item.getRate(), item.getSalePrice(), item.getMarkupAmount(), plan);
        item.setMode(resolved.mode());
        item.setSalePrice(resolved.salePrice());
        if ("price".equals(resolved.mode())) item.setRate(0);
        commissionMapper.update(item);
        auditService.log(user, "update", "agent_commission", String.valueOf(id));
        return commissionDict(item);
    }

    @DeleteMapping("/agent-commissions/{id}")
    public Map<String, Boolean> deleteCommission(@PathVariable int id, User user) {
        Rbac.requireRole(user, "仅总后台可删除佣金关系", "admin");
        if (commissionMapper.findById(id) == null) throw ApiException.notFound("佣金关系不存在");
        commissionMapper.delete(id);
        auditService.log(user, "delete", "agent_commission", String.valueOf(id));
        return Map.of("ok", true);
    }
}
