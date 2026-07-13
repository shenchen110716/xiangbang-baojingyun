package com.xbb.baojing.dashboard;

import com.xbb.baojing.claim.ClaimMapper;
import com.xbb.baojing.common.User;
import com.xbb.baojing.enterprise.Enterprise;
import com.xbb.baojing.enterprise.EnterpriseMapper;
import com.xbb.baojing.insured.InsuredPersonMapper;
import com.xbb.baojing.insured.Policy;
import com.xbb.baojing.insured.PolicyMapper;
import com.xbb.baojing.insured.PolicyPricingService;
import com.xbb.baojing.plan.PricingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ReportsController {
    private final PolicyMapper policyMapper;
    private final InsuredPersonMapper personMapper;
    private final ClaimMapper claimMapper;
    private final EnterpriseMapper enterpriseMapper;
    private final PolicyPricingService policyPricingService;

    public ReportsController(PolicyMapper policyMapper, InsuredPersonMapper personMapper, ClaimMapper claimMapper,
                              EnterpriseMapper enterpriseMapper, PolicyPricingService policyPricingService) {
        this.policyMapper = policyMapper;
        this.personMapper = personMapper;
        this.claimMapper = claimMapper;
        this.enterpriseMapper = enterpriseMapper;
        this.policyPricingService = policyPricingService;
    }

    public record ReportRow(String id, String name, String period, double value, String detail) {}

    @GetMapping("/reports")
    public List<ReportRow> reports(User user) {
        Integer scoped = "enterprise".equals(user.getRole()) ? user.getEnterpriseId() : null;
        List<Policy> policies = policyMapper.search(scoped);
        long peopleCount = personMapper.search(scoped, null).size();
        long claimsCount = claimMapper.search(scoped, null).size();

        YearMonth now = YearMonth.now();
        int days = now.lengthOfMonth();
        LocalDate monthStart = now.atDay(1);
        LocalDate monthEnd = now.atEndOfMonth();

        double premium = 0, settlement = 0, commission = 0;
        for (Policy p : policies) {
            var totals = policyPricingService.totals(p);
            LocalDate start = safeDate(p.getStartDate(), monthStart);
            LocalDate end = safeDate(p.getEndDate(), monthEnd);
            LocalDate rangeStart = start.isAfter(monthStart) ? start : monthStart;
            LocalDate rangeEnd = end.isBefore(monthEnd) ? end : monthEnd;
            long activeDays = Math.max(0, rangeEnd.toEpochDay() - rangeStart.toEpochDay() + 1);
            premium += totals.salePrice() * activeDays / days;
            settlement += totals.policyFloor();
            commission += totals.totalCommission();
        }
        String period = now.getYear() + "-" + String.format("%02d", now.getMonthValue()) + "按实际天数";
        return List.of(
                new ReportRow("premium", "销售保费汇总", period, PricingService.amount(premium), policies.size() + " 张保单，统一按销售价格计算"),
                new ReportRow("settlement", "保司结算底价", "当前", PricingService.amount(settlement), "保险原价 ×（1-总返佣比例）"),
                new ReportRow("commission", "总返佣金额", "当前", PricingService.amount(commission), "保险原价 × 总返佣比例"),
                new ReportRow("people", "参保人员报表", "当前", peopleCount, "在册参保人员"),
                new ReportRow("claims", "理赔统计报表", "累计", claimsCount, "理赔案件"));
    }

    private LocalDate safeDate(String s, LocalDate fallback) {
        try { return s == null || s.isBlank() ? fallback : LocalDate.parse(s); } catch (Exception e) { return fallback; }
    }

    public record BillingRow(int id, String enterpriseName, String account, double balance, String status,
                              double dailyRate, double estimatedDaily, Double monthlyEstimate) {}

    @GetMapping("/billing")
    public List<BillingRow> billing(User user) {
        List<Enterprise> enterprises = "enterprise".equals(user.getRole()) && user.getEnterpriseId() != null
                ? List.of(enterpriseMapper.findById(user.getEnterpriseId()))
                : enterpriseMapper.search(null, null, null);
        List<BillingRow> rows = new ArrayList<>();
        int days = YearMonth.now().lengthOfMonth();
        for (Enterprise e : enterprises) {
            long activeCount = personMapper.search(e.getId(), null).stream()
                    .filter(p -> java.util.Set.of("active", "pending").contains(p.getStatus())).count();
            double dailyUsage = activeCount * (e.getUsageFeeDaily() > 0 ? e.getUsageFeeDaily() : 0.1);
            rows.add(new BillingRow(e.getId(), e.getName(), "保费账户", e.getPremiumBalance(), "正常", 0, 0, null));
            rows.add(new BillingRow(e.getId(), e.getName(), "平台使用费账户", e.getUsageBalance(), "正常",
                    e.getUsageFeeDaily() > 0 ? e.getUsageFeeDaily() : 0.1, dailyUsage, dailyUsage * days));
        }
        return rows;
    }
}
