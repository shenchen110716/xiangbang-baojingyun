package com.xbb.baojing.enterprise;

import com.xbb.baojing.agent.AgentCommission;
import com.xbb.baojing.agent.AgentCommissionMapper;
import com.xbb.baojing.common.*;
import com.xbb.baojing.insured.InsuredPersonMapper;
import com.xbb.baojing.insured.Policy;
import com.xbb.baojing.insured.PolicyMapper;
import com.xbb.baojing.plan.InsurancePlan;
import com.xbb.baojing.plan.InsurancePlanMapper;
import com.xbb.baojing.plan.PricingService;
import com.xbb.baojing.finance.LedgerEntry;
import com.xbb.baojing.finance.LedgerMapper;
import com.xbb.baojing.finance.LedgerService;
import com.xbb.baojing.position.WorkPositionMapper;
import com.xbb.baojing.recharge.RechargeService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api")
public class EnterpriseController {
    private final EnterpriseMapper enterpriseMapper;
    private final ActualEmployerMapper actualEmployerMapper;
    private final AgentCommissionMapper commissionMapper;
    private final UserMapper userMapper;
    private final InsuredPersonMapper personMapper;
    private final PolicyMapper policyMapper;
    private final InsurancePlanMapper planMapper;
    private final WorkPositionMapper positionMapper;
    private final LedgerMapper ledgerMapper;
    private final LedgerService ledgerService;
    private final PricingService pricingService;
    private final AuditService auditService;
    private final PasswordEncoder passwordEncoder;
    private final RechargeService rechargeService;

    public EnterpriseController(EnterpriseMapper enterpriseMapper, ActualEmployerMapper actualEmployerMapper,
                                 AgentCommissionMapper commissionMapper, UserMapper userMapper, InsuredPersonMapper personMapper,
                                 PolicyMapper policyMapper, InsurancePlanMapper planMapper, WorkPositionMapper positionMapper,
                                 LedgerMapper ledgerMapper, LedgerService ledgerService, PricingService pricingService,
                                 AuditService auditService, PasswordEncoder passwordEncoder, RechargeService rechargeService) {
        this.enterpriseMapper = enterpriseMapper;
        this.actualEmployerMapper = actualEmployerMapper;
        this.commissionMapper = commissionMapper;
        this.userMapper = userMapper;
        this.personMapper = personMapper;
        this.policyMapper = policyMapper;
        this.planMapper = planMapper;
        this.positionMapper = positionMapper;
        this.ledgerMapper = ledgerMapper;
        this.ledgerService = ledgerService;
        this.pricingService = pricingService;
        this.auditService = auditService;
        this.passwordEncoder = passwordEncoder;
        this.rechargeService = rechargeService;
    }

    public record EnterpriseIn(String name, String kind, String contact, String phone, String creditCode,
                                Integer agentId, Double usageFeeDaily, Integer alertDays) {}

    public record EnterpriseUpdate(String name, String kind, String contact, String phone, String creditCode,
                                    Integer agentId, Double usageFeeDaily, Integer alertDays) {}

    public record RechargeIn(String account, double amount) {}

    @GetMapping("/enterprises")
    public List<Enterprise> list(@RequestParam(defaultValue = "") String q, @RequestParam(name = "status", required = false) String status, User user) {
        Integer scoped = "enterprise".equals(user.getRole()) && user.getEnterpriseId() != null ? user.getEnterpriseId() : null;
        List<Enterprise> rows = enterpriseMapper.search(scoped, q, status);
        for (Enterprise e : rows) {
            Integer agentId = e.getAgentId();
            if (agentId == null) {
                AgentCommission linked = commissionMapper.findFirstByEnterprise(e.getId());
                agentId = linked != null ? linked.getAgentId() : null;
            }
            e.setAgentId(agentId);
            User agent = agentId != null ? userMapper.findById(agentId) : null;
            e.setAgentName(agent != null ? agent.getName() : "未分配");
            double premiumTotal = rechargeService.premiumAccountsForEnterprise(e.getId()).stream()
                    .mapToDouble(RechargeService.PremiumAccountRow::balance).sum();
            e.setPremiumBalanceTotal(premiumTotal);
        }
        return rows;
    }

    @PostMapping("/enterprises")
    public Enterprise create(@RequestBody EnterpriseIn data, User user) {
        Rbac.requireRole(user, "仅总后台可新增投保单位", "admin");
        if (data.agentId() != null) {
            User agent = userMapper.findById(data.agentId());
            if (agent == null || !"salesperson".equals(agent.getRole())) throw ApiException.notFound("业务员不存在");
        }
        Enterprise e = new Enterprise();
        e.setName(data.name());
        if (data.kind() != null) e.setKind(data.kind());
        if (data.contact() != null) e.setContact(data.contact());
        if (data.phone() != null) e.setPhone(data.phone());
        if (data.creditCode() != null) e.setCreditCode(data.creditCode());
        e.setAgentId(data.agentId());
        if (data.usageFeeDaily() != null) e.setUsageFeeDaily(data.usageFeeDaily());
        if (data.alertDays() != null) e.setAlertDays(data.alertDays());
        e.setStatus("pending");
        e.setCreatedAt(LocalDateTime.now());
        enterpriseMapper.insert(e);
        auditService.log(user, "create", "enterprise", String.valueOf(e.getId()));
        return e;
    }

    @PatchMapping("/enterprises/{id}/status")
    public Enterprise setStatus(@PathVariable int id, @RequestParam("status") String status, User user) {
        Rbac.requireRole(user, "仅总后台可审核投保单位", "admin");
        Enterprise e = enterpriseMapper.findById(id);
        if (e == null) throw ApiException.notFound("企业不存在");
        e.setStatus(status);
        enterpriseMapper.update(e);
        auditService.log(user, "status_change", "enterprise", String.valueOf(id), status);
        return e;
    }

    @PatchMapping("/enterprises/{id}")
    public Enterprise update(@PathVariable int id, @RequestBody EnterpriseUpdate data, User user) {
        Enterprise e = enterpriseMapper.findById(id);
        if (e == null) throw ApiException.notFound("投保单位不存在");
        if (!user.getRole().equals("admin") && !user.getRole().equals("enterprise")) throw ApiException.forbidden("无权操作投保单位");
        if ("enterprise".equals(user.getRole()) && !Integer.valueOf(id).equals(user.getEnterpriseId())) throw ApiException.forbidden("无权操作该单位");
        if (data.agentId() != null) {
            User agent = userMapper.findById(data.agentId());
            if (agent == null || !"salesperson".equals(agent.getRole())) throw ApiException.notFound("业务员不存在");
            List<AgentCommission> existing = commissionMapper.findByEnterprise(id);
            if (!existing.isEmpty() && existing.stream().anyMatch(c -> !c.getAgentId().equals(data.agentId()))) {
                throw ApiException.conflict("一个投保单位只能关联一个业务员；该单位已关联其他业务员");
            }
        }
        if (data.name() != null) e.setName(data.name());
        if (data.kind() != null) e.setKind(data.kind());
        if (data.contact() != null) e.setContact(data.contact());
        if (data.phone() != null) e.setPhone(data.phone());
        if (data.creditCode() != null) e.setCreditCode(data.creditCode());
        if (data.agentId() != null) e.setAgentId(data.agentId());
        if (data.usageFeeDaily() != null) e.setUsageFeeDaily(data.usageFeeDaily());
        if (data.alertDays() != null) e.setAlertDays(data.alertDays());
        enterpriseMapper.update(e);
        auditService.log(user, "update", "enterprise", String.valueOf(id));
        return e;
    }

    @DeleteMapping("/enterprises/{id}")
    public Map<String, Boolean> delete(@PathVariable int id, User user) {
        Rbac.requireRole(user, "仅总后台可删除投保单位", "admin");
        Enterprise e = enterpriseMapper.findById(id);
        if (e == null) throw ApiException.notFound("投保单位不存在");
        if (personMapper.search(id, null).size() > 0) throw ApiException.conflict("该单位已有参保人员或保单，不能删除；请先停保并归档");
        if (policyMapper.search(id).size() > 0) throw ApiException.conflict("该单位已有参保人员或保单，不能删除；请先停保并归档");
        enterpriseMapper.delete(id);
        auditService.log(user, "delete", "enterprise", String.valueOf(id));
        return Map.of("ok", true);
    }

    @PostMapping("/enterprises/{id}/recharge")
    public Enterprise recharge(@PathVariable int id, @RequestBody RechargeIn data, User user) {
        Rbac.requireRole(user, "企业账户不支持自助充值，请联系平台完成充值审核", "admin");
        Enterprise e = enterpriseMapper.findById(id);
        if (e == null) throw ApiException.notFound("投保单位不存在");
        if (!Set.of("premium", "usage").contains(data.account())) throw ApiException.badRequest("账户类型不合法");
        if ("premium".equals(data.account())) throw ApiException.badRequest("保费账户充值请使用「账户充值」页面提交充值申请，走审核流程");
        e.setUsageBalance(e.getUsageBalance() + data.amount());
        ledgerService.postEntry(e, data.account(), "credit", data.amount(), "manual_recharge", String.valueOf(id), user);
        enterpriseMapper.update(e);
        auditService.log(user, "recharge", "enterprise", String.valueOf(id), data.account() + ":" + data.amount());
        return e;
    }

    @GetMapping("/enterprises/{id}/premium-accounts")
    public List<RechargeService.PremiumAccountRow> premiumAccounts(@PathVariable int id, User user) {
        if ("enterprise".equals(user.getRole()) && !Integer.valueOf(id).equals(user.getEnterpriseId())) throw ApiException.forbidden("无权查看该单位账户");
        if (enterpriseMapper.findById(id) == null) throw ApiException.notFound("投保单位不存在");
        return rechargeService.premiumAccountsForEnterprise(id);
    }

    public record LedgerResponse(List<LedgerEntry> entries, List<LedgerService.Mismatch> reconciliation) {}

    @GetMapping("/enterprises/{id}/ledger")
    public LedgerResponse ledger(@PathVariable int id, User user) {
        Enterprise e = enterpriseMapper.findById(id);
        if (e == null) throw ApiException.notFound("投保单位不存在");
        if ("enterprise".equals(user.getRole()) && !Integer.valueOf(id).equals(user.getEnterpriseId())) throw ApiException.forbidden("无权查看该单位账本");
        return new LedgerResponse(ledgerMapper.findByEnterprise(id), ledgerService.reconcile(e));
    }

    public record AdminOut(int id, String username, String name, String phone, boolean active) {}

    @GetMapping("/enterprises/{id}/admins")
    public List<AdminOut> admins(@PathVariable int id, User user) {
        if (!user.getRole().equals("admin") && !user.getRole().equals("enterprise")) throw ApiException.forbidden("无权查看单位管理员");
        if ("enterprise".equals(user.getRole()) && !Integer.valueOf(id).equals(user.getEnterpriseId())) throw ApiException.forbidden("无权查看该单位");
        return userMapper.findOperators(id).stream().map(u -> new AdminOut(u.getId(), u.getUsername(), u.getName(), u.getPhone(), u.isActive())).toList();
    }

    @PostMapping("/enterprises/{id}/admins")
    public AdminOut addAdmin(@PathVariable int id, @RequestBody CommonDtos.AgentIn data, User user) {
        Rbac.requireRole(user, "仅总后台可管理单位管理员", "admin");
        if (enterpriseMapper.findById(id) == null) throw ApiException.notFound("投保单位不存在");
        if (userMapper.countByUsername(data.username()) > 0) throw ApiException.conflict("账号已存在");
        User newUser = new User();
        newUser.setUsername(data.username());
        newUser.setPasswordHash(passwordEncoder.encode(data.password()));
        newUser.setName(data.name());
        newUser.setPhone(data.phone() == null ? "" : data.phone());
        newUser.setRole("enterprise");
        newUser.setEnterpriseId(id);
        newUser.setCreatedAt(LocalDateTime.now());
        userMapper.insert(newUser);
        auditService.log(user, "create", "enterprise_admin", String.valueOf(newUser.getId()));
        return new AdminOut(newUser.getId(), newUser.getUsername(), newUser.getName(), newUser.getPhone(), newUser.isActive());
    }

    public record ProductRow(int id, String product, String insurer, String agent, double commissionRate,
                              long insuredCount, double premiumTotal, String status) {}

    @GetMapping("/enterprises/{id}/products")
    public List<Map<String, Object>> products(@PathVariable int id, User user) {
        if ("enterprise".equals(user.getRole()) && !Integer.valueOf(id).equals(user.getEnterpriseId())) throw ApiException.forbidden("无权查看该单位");
        if (enterpriseMapper.findById(id) == null) throw ApiException.notFound("投保单位不存在");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AgentCommission c : commissionMapper.findByEnterprise(id)) {
            InsurancePlan plan = planMapper.findById(c.getPlanId());
            User agent = userMapper.findById(c.getAgentId());
            long insuredCount = personMapper.search(id, null).stream()
                    .filter(p -> !"stopped".equals(p.getStatus()))
                    .filter(p -> p.getPositionId() != null && positionMapper.findById(p.getPositionId()) != null
                            && Objects.equals(positionMapper.findById(p.getPositionId()).getPlanId(), c.getPlanId()))
                    .count();
            double premiumTotal = policyMapper.findPremiumsForEnterprisePlan(id, c.getPlanId()).stream().mapToDouble(Double::doubleValue).sum();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", c.getId());
            row.put("product", plan != null ? plan.getName() : "");
            row.put("insurer", plan != null ? plan.getInsurer() : "");
            row.put("agent", agent != null ? agent.getName() : "");
            row.put("commission_rate", c.getRate());
            row.put("insured_count", insuredCount);
            row.put("premium_total", premiumTotal);
            row.put("status", c.getStatus());
            if (plan != null) {
                var snap = pricingService.snapshot(plan, c);
                row.put("insurance_base_price", snap.getInsuranceBasePrice());
                row.put("total_commission_rate", snap.getTotalCommissionRate());
                row.put("total_commission_amount", snap.getTotalCommissionAmount());
                row.put("policy_floor_price", snap.getPolicyFloorPrice());
                row.put("profit_amount", snap.getProfitAmount());
                row.put("minimum_sale_price", snap.getMinimumSalePrice());
                row.put("sale_price", snap.getSalePrice());
                row.put("agent_commission_amount", snap.getAgentCommissionAmount());
            }
            if ("enterprise".equals(user.getRole())) InternalPricingFilter.INTERNAL_FIELDS.forEach(row::remove);
            rows.add(row);
        }
        return rows;
    }
}
