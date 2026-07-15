package com.xbb.baojing.dashboard;

import com.xbb.baojing.agent.AgentCommission;
import com.xbb.baojing.agent.AgentCommissionMapper;
import com.xbb.baojing.claim.ClaimMapper;
import com.xbb.baojing.common.InternalPricingFilter;
import com.xbb.baojing.common.User;
import com.xbb.baojing.enterprise.Enterprise;
import com.xbb.baojing.enterprise.EnterpriseMapper;
import com.xbb.baojing.insured.InsuredPerson;
import com.xbb.baojing.insured.InsuredPersonMapper;
import com.xbb.baojing.insured.Policy;
import com.xbb.baojing.insured.PolicyMapper;
import com.xbb.baojing.insured.PolicyMember;
import com.xbb.baojing.insured.PolicyMemberMapper;
import com.xbb.baojing.insured.PolicyMemberService;
import com.xbb.baojing.insured.PolicyPricingService;
import com.xbb.baojing.plan.InsurancePlan;
import com.xbb.baojing.plan.InsurancePlanMapper;
import com.xbb.baojing.plan.PricingService;
import com.xbb.baojing.position.WorkPosition;
import com.xbb.baojing.position.WorkPositionMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

@RestController
@RequestMapping("/api")
public class DashboardController {
    private final EnterpriseMapper enterpriseMapper;
    private final InsuredPersonMapper personMapper;
    private final PolicyMapper policyMapper;
    private final ClaimMapper claimMapper;
    private final InsurancePlanMapper planMapper;
    private final AgentCommissionMapper commissionMapper;
    private final WorkPositionMapper positionMapper;
    private final PolicyPricingService policyPricingService;
    private final PricingService pricingService;
    private final PolicyMemberMapper policyMemberMapper;
    private final PolicyMemberService policyMemberService;

    public DashboardController(EnterpriseMapper enterpriseMapper, InsuredPersonMapper personMapper, PolicyMapper policyMapper,
                                ClaimMapper claimMapper, InsurancePlanMapper planMapper, AgentCommissionMapper commissionMapper,
                                WorkPositionMapper positionMapper, PolicyPricingService policyPricingService, PricingService pricingService,
                                PolicyMemberMapper policyMemberMapper, PolicyMemberService policyMemberService) {
        this.enterpriseMapper = enterpriseMapper;
        this.personMapper = personMapper;
        this.policyMapper = policyMapper;
        this.claimMapper = claimMapper;
        this.planMapper = planMapper;
        this.commissionMapper = commissionMapper;
        this.positionMapper = positionMapper;
        this.policyPricingService = policyPricingService;
        this.pricingService = pricingService;
        this.policyMemberMapper = policyMemberMapper;
        this.policyMemberService = policyMemberService;
    }

    private String effectiveStatus(InsuredPerson p) {
        if (!"stopped".equals(p.getStatus())) return p.getStatus();
        PolicyMember member = policyMemberMapper.findLatestForPerson(p.getId());
        return policyMemberService.effectivePersonStatus(p, member != null ? member.getTerminatedAt() : null);
    }

    public record BalanceAlert(int enterpriseId, String enterpriseName, String account, double balance, double dailyBurn,
                                double daysLeft, int alertDays, String level) {}

    public record DashboardData(String portal, long enterprises, long people, long activePeople, long activePolicies,
                                 long pendingEnterprises, long pendingPeople, long claimsOpen, double premiumBalance,
                                 double usageBalance, List<BalanceAlert> balanceAlerts) {}

    @GetMapping("/dashboard")
    public DashboardData dashboard(User user) {
        Integer scoped = "enterprise".equals(user.getRole()) && user.getEnterpriseId() != null ? user.getEnterpriseId() : null;
        List<Enterprise> enterprises = scoped != null ? List.of(enterpriseMapper.findById(scoped)) : enterpriseMapper.search(null, null, null);
        List<InsuredPerson> people = personMapper.search(scoped, null);
        long activePeople = people.stream().filter(p -> Set.of("active", "pending").contains(effectiveStatus(p))).count();

        List<BalanceAlert> alerts = new ArrayList<>();
        for (Enterprise ent : enterprises) {
            long activeCount = personMapper.search(ent.getId(), null).stream().filter(p -> Set.of("active", "pending").contains(p.getStatus())).count();
            double dailyUsage = activeCount * (ent.getUsageFeeDaily() > 0 ? ent.getUsageFeeDaily() : 0.1);
            double dailyPremium = 0;
            for (Policy policy : policyMapper.search(ent.getId())) {
                if (!"active".equals(policy.getStatus())) continue;
                InsurancePlan plan = planMapper.findById(policy.getPlanId());
                double premium = policyPricingService.calculatedPremium(policy);
                boolean daily = plan != null && "daily".equals(plan.getBillingMode());
                dailyPremium += premium / (daily ? 1 : 30);
            }
            record AccountCheck(String account, double balance, double daily) {}
            for (AccountCheck check : List.of(new AccountCheck("premium", ent.getPremiumBalance(), dailyPremium), new AccountCheck("usage", ent.getUsageBalance(), dailyUsage))) {
                double daysLeft = check.daily() <= 0 ? 999999 : check.balance() / check.daily();
                int alertDays = ent.getAlertDays() > 0 ? ent.getAlertDays() : 3;
                if (daysLeft <= alertDays) {
                    alerts.add(new BalanceAlert(ent.getId(), ent.getName(), check.account(), check.balance(), check.daily(),
                            Math.round(daysLeft * 10) / 10.0, alertDays, daysLeft <= 1 ? "critical" : "warning"));
                }
            }
        }

        long activePolicies = scoped != null
                ? policyMapper.search(scoped).stream().filter(p -> "active".equals(p.getStatus())).count()
                : policyMapper.search(null).stream().filter(p -> "active".equals(p.getStatus())).count();
        long pendingEnterprises = scoped == null ? enterpriseMapper.search(null, null, "pending").size() : 0;
        long pendingPeople = people.stream().filter(p -> "pending".equals(p.getStatus())).count();
        long claimsOpen = claimMapper.search(scoped, null).stream().filter(c -> !Set.of("paid", "closed").contains(c.getStatus())).count();
        double premiumBalance = enterprises.stream().mapToDouble(Enterprise::getPremiumBalance).sum();
        double usageBalance = enterprises.stream().mapToDouble(Enterprise::getUsageBalance).sum();

        return new DashboardData("enterprise".equals(user.getRole()) ? "enterprise" : "admin", enterprises.size(), people.size(),
                activePeople, activePolicies, pendingEnterprises, pendingPeople, claimsOpen, premiumBalance, usageBalance, alerts);
    }

    public record ScreenProduct(int planId, String insurer, String product, long insuredCount, long enterpriseCount,
                                 double premiumTotal, long policyCount) {}

    @GetMapping("/screen/products")
    public List<Map<String, Object>> screenProducts(User user) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (InsurancePlan plan : planMapper.findAll()) {
            List<Policy> policies = policyMapper.search(null).stream().filter(p -> p.getPlanId().equals(plan.getId())).toList();
            if ("enterprise".equals(user.getRole()) && user.getEnterpriseId() != null) {
                policies = policies.stream().filter(p -> p.getEnterpriseId().equals(user.getEnterpriseId())).toList();
            }
            List<InsuredPerson> people = personMapper.search(null, null).stream()
                    .filter(p -> Set.of("active", "pending").contains(p.getStatus()))
                    .filter(p -> p.getPositionId() != null)
                    .filter(p -> { WorkPosition pos = positionMapper.findById(p.getPositionId()); return pos != null && Objects.equals(pos.getPlanId(), plan.getId()); })
                    .toList();
            if ("enterprise".equals(user.getRole()) && user.getEnterpriseId() != null) {
                people = people.stream().filter(p -> p.getEnterpriseId().equals(user.getEnterpriseId())).toList();
            }
            Set<Integer> enterpriseIds = new HashSet<>();
            people.forEach(p -> enterpriseIds.add(p.getEnterpriseId()));
            policies.forEach(p -> enterpriseIds.add(p.getEnterpriseId()));
            double premiumTotal = policies.stream().mapToDouble(policyPricingService::calculatedPremium).sum();

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("plan_id", plan.getId());
            row.put("insurer", plan.getInsurer());
            row.put("product", plan.getName());
            row.put("insured_count", people.size());
            row.put("enterprise_count", enterpriseIds.size());
            row.put("premium_total", PricingService.amount(premiumTotal));
            row.put("policy_count", policies.size());
            var snap = pricingService.snapshot(plan);
            row.put("insurance_base_price", snap.getInsuranceBasePrice());
            row.put("total_commission_rate", snap.getTotalCommissionRate());
            row.put("total_commission_amount", snap.getTotalCommissionAmount());
            row.put("policy_floor_price", snap.getPolicyFloorPrice());
            row.put("insurer_settlement_price", snap.getInsurerSettlementPrice());
            row.put("profit_amount", snap.getProfitAmount());
            row.put("minimum_sale_price", snap.getMinimumSalePrice());
            row.put("commission_mode", snap.getCommissionMode());
            row.put("agent_commission_rate", snap.getAgentCommissionRate());
            row.put("agent_commission_amount", snap.getAgentCommissionAmount());
            row.put("sale_price", snap.getSalePrice());
            row.put("platform_margin_amount", snap.getPlatformMarginAmount());
            if ("enterprise".equals(user.getRole())) InternalPricingFilter.INTERNAL_FIELDS.forEach(row::remove);
            result.add(row);
        }
        return result;
    }
}
