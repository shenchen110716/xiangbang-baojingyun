package com.xbb.baojing.insured;

import com.xbb.baojing.agent.AgentCommission;
import com.xbb.baojing.agent.AgentCommissionMapper;
import com.xbb.baojing.plan.InsurancePlan;
import com.xbb.baojing.plan.InsurancePlanMapper;
import com.xbb.baojing.plan.PricingService;
import com.xbb.baojing.plan.PricingSnapshot;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

/** The premium-calculation half of services/policies.py's policy_dict(),
 * factored out so both PolicyController (full policy list/export) and
 * DashboardController (daily premium burn-rate for balance alerts) can
 * compute the same real, sale-price-based premium instead of duplicating
 * the join+aggregate logic. */
@Service
public class PolicyPricingService {
    private final InsurancePlanMapper planMapper;
    private final AgentCommissionMapper commissionMapper;
    private final InsuredPersonMapper personMapper;
    private final PricingService pricingService;

    public PolicyPricingService(InsurancePlanMapper planMapper, AgentCommissionMapper commissionMapper,
                                 InsuredPersonMapper personMapper, PricingService pricingService) {
        this.planMapper = planMapper;
        this.commissionMapper = commissionMapper;
        this.personMapper = personMapper;
        this.pricingService = pricingService;
    }

    /** Returns the calculated (real, sale-price-based) premium for a policy —
     * SUM(sale_price) across its insured people, or the raw stored premium
     * if it has none yet. */
    public double calculatedPremium(Policy policy) {
        return totals(policy).salePrice();
    }

    public record Totals(double salePrice, double policyFloor, double totalCommission) {}

    /** Ports the totals half of policy_dict() — used by ReportsController's
     * /reports (sales premium / settlement / commission summary cards). */
    public Totals totals(Policy policy) {
        InsurancePlan plan = planMapper.findById(policy.getPlanId());
        if (plan == null) return new Totals(policy.getPremium(), 0, 0);
        AgentCommission relation = commissionMapper.findActiveRelation(policy.getEnterpriseId(), policy.getPlanId());
        List<InsuredPerson> people = personMapper.findByPolicy(policy.getId());
        if (people.isEmpty()) return new Totals(policy.getPremium(), 0, 0);
        List<PricingSnapshot> snapshots = new ArrayList<>();
        for (InsuredPerson p : people) snapshots.add(pricingService.snapshot(plan, relation, pricingService.planPriceForClass(plan, p.getOccupationClass())));
        ToDoubleFunction<PricingSnapshot> sale = PricingSnapshot::getSalePrice;
        ToDoubleFunction<PricingSnapshot> floor = PricingSnapshot::getPolicyFloorPrice;
        ToDoubleFunction<PricingSnapshot> commission = PricingSnapshot::getTotalCommissionAmount;
        return new Totals(
                PricingService.amount(snapshots.stream().mapToDouble(sale).sum()),
                PricingService.amount(snapshots.stream().mapToDouble(floor).sum()),
                PricingService.amount(snapshots.stream().mapToDouble(commission).sum()));
    }
}
