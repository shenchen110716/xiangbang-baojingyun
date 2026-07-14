package com.xbb.baojing.insured;

import com.xbb.baojing.agent.AgentCommission;
import com.xbb.baojing.agent.AgentCommissionMapper;
import com.xbb.baojing.plan.InsurancePlan;
import com.xbb.baojing.plan.InsurancePlanMapper;
import com.xbb.baojing.plan.PricingService;
import com.xbb.baojing.plan.PricingSnapshot;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    private final PolicyMemberMapper policyMemberMapper;
    private final PricingService pricingService;

    public PolicyPricingService(InsurancePlanMapper planMapper, AgentCommissionMapper commissionMapper,
                                 InsuredPersonMapper personMapper, PolicyMemberMapper policyMemberMapper, PricingService pricingService) {
        this.planMapper = planMapper;
        this.commissionMapper = commissionMapper;
        this.personMapper = personMapper;
        this.policyMemberMapper = policyMemberMapper;
        this.pricingService = pricingService;
    }

    private double periodAmount(double unitPrice, String billingMode, LocalDate start, LocalDate end) {
        if (start.isAfter(end)) return 0;
        if ("daily".equals(billingMode)) return unitPrice * (ChronoUnit.DAYS.between(start, end) + 1);
        double total = 0;
        LocalDate cursor = start;
        while (!cursor.isAfter(end)) {
            YearMonth month = YearMonth.from(cursor);
            LocalDate segmentEnd = end.isBefore(month.atEndOfMonth()) ? end : month.atEndOfMonth();
            total += unitPrice * (ChronoUnit.DAYS.between(cursor, segmentEnd) + 1) / month.lengthOfMonth();
            cursor = segmentEnd.plusDays(1);
        }
        return total;
    }

    /** Intersects a member's coverage window with [periodStart, today], same
     * "a midnight termination ends coverage the day before" rule used
     * elsewhere. Returns null if the member had no billable days in that
     * window (not yet effective, or terminated before periodStart). */
    private LocalDate[] billableRange(PolicyMember member, LocalDate periodStart, LocalDate today) {
        LocalDate effectiveDate = member.getEffectiveAt().toLocalDate();
        if (effectiveDate.isAfter(today)) return null;
        LocalDate end = today;
        if (member.getTerminatedAt() != null) {
            LocalDate terminated = member.getTerminatedAt().toLocalDate();
            if (LocalTime.MIDNIGHT.equals(member.getTerminatedAt().toLocalTime())) terminated = terminated.minusDays(1);
            if (terminated.isBefore(end)) end = terminated;
        }
        LocalDate start = effectiveDate.isAfter(periodStart) ? effectiveDate : periodStart;
        return start.isAfter(end) ? null : new LocalDate[]{start, end};
    }

    /** Returns the calculated (real, sale-price-based) premium for a policy —
     * SUM(sale_price) across its insured people, or the raw stored premium
     * if it has none yet. */
    public double calculatedPremium(Policy policy) {
        return totals(policy).salePrice();
    }

    /** Historical report fallback for legacy PolicyMember rows that predate
     * rate_snapshot_json. New rows always use their frozen snapshot instead. */
    public double salePriceFor(Policy policy, InsuredPerson person) {
        PricingSnapshot snapshot = pricingFor(policy, person);
        return snapshot != null ? snapshot.getSalePrice() : policy.getPremium();
    }

    /** Returns both sales and insurer-settlement prices from one calculation,
     * so period reports can prorate them with exactly the same billing rules. */
    public PricingSnapshot pricingFor(Policy policy, InsuredPerson person) {
        InsurancePlan plan = planMapper.findById(policy.getPlanId());
        if (plan == null) return null;
        AgentCommission relation = commissionMapper.findActiveRelation(policy.getEnterpriseId(), policy.getPlanId());
        return pricingService.snapshot(plan, relation, pricingService.planPriceForClass(plan, person.getOccupationClass()));
    }

    public record Totals(double salePrice, double policyFloor, double totalCommission) {}
    public record BilledRow(PricingSnapshot snapshot, double ratio) {}
    public record CurrentMonthBilling(List<BilledRow> rows, int personCount) {}

    /** The current-calendar-month, day-prorated roster for a policy: every
     * person with at least one billable day this month, including ones who
     * have since been terminated (their days up to the stop date still owe
     * premium — see feedback item 8). Each row's ratio is the fraction of a
     * full unit price owed for the person's billable days this month (a day
     * count for daily billing_mode, a month-fraction for monthly). */
    public CurrentMonthBilling currentMonthBilling(Policy policy) {
        InsurancePlan plan = planMapper.findById(policy.getPlanId());
        if (plan == null) return new CurrentMonthBilling(List.of(), 0);
        AgentCommission relation = commissionMapper.findActiveRelation(policy.getEnterpriseId(), policy.getPlanId());
        LocalDate today = LocalDate.now();
        LocalDate periodStart = today.withDayOfMonth(1);
        List<BilledRow> rows = new ArrayList<>();
        Set<Integer> personIds = new HashSet<>();
        for (PolicyMember member : policyMemberMapper.findByPolicy(policy.getId())) {
            LocalDate[] range = billableRange(member, periodStart, today);
            if (range == null) continue;
            InsuredPerson person = personMapper.findById(member.getPersonId());
            if (person == null) continue;
            double ratio = periodAmount(1.0, plan.getBillingMode(), range[0], range[1]);
            PricingSnapshot snapshot = pricingService.snapshot(plan, relation, pricingService.planPriceForClass(plan, person.getOccupationClass()));
            rows.add(new BilledRow(snapshot, ratio));
            personIds.add(person.getId());
        }
        return new CurrentMonthBilling(rows, personIds.size());
    }

    /** Ports the totals half of policy_dict() — used by ReportsController's
     * /reports (sales premium / settlement / commission summary cards) and
     * DashboardController's premium burn-rate. Prorated by current-month
     * billable days per currentMonthBilling(), including people who were
     * terminated partway through the month (feedback item 8). */
    public Totals totals(Policy policy) {
        CurrentMonthBilling billing = currentMonthBilling(policy);
        if (billing.rows().isEmpty()) return new Totals(policy.getPremium(), 0, 0);
        ToDoubleFunction<BilledRow> sale = r -> r.snapshot().getSalePrice() * r.ratio();
        ToDoubleFunction<BilledRow> floor = r -> r.snapshot().getPolicyFloorPrice() * r.ratio();
        ToDoubleFunction<BilledRow> commission = r -> r.snapshot().getTotalCommissionAmount() * r.ratio();
        return new Totals(
                PricingService.amount(billing.rows().stream().mapToDouble(sale).sum()),
                PricingService.amount(billing.rows().stream().mapToDouble(floor).sum()),
                PricingService.amount(billing.rows().stream().mapToDouble(commission).sum()));
    }
}
