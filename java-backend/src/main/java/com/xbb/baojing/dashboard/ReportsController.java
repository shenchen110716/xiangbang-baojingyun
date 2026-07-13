package com.xbb.baojing.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbb.baojing.claim.ClaimMapper;
import com.xbb.baojing.common.ApiException;
import com.xbb.baojing.common.User;
import com.xbb.baojing.common.UserMapper;
import com.xbb.baojing.enterprise.ActualEmployer;
import com.xbb.baojing.enterprise.ActualEmployerMapper;
import com.xbb.baojing.enterprise.Enterprise;
import com.xbb.baojing.enterprise.EnterpriseMapper;
import com.xbb.baojing.insured.InsuredPerson;
import com.xbb.baojing.insured.InsuredPersonMapper;
import com.xbb.baojing.insured.Policy;
import com.xbb.baojing.insured.PolicyMapper;
import com.xbb.baojing.insured.PolicyMember;
import com.xbb.baojing.insured.PolicyMemberMapper;
import com.xbb.baojing.insured.PolicyPricingService;
import com.xbb.baojing.plan.InsurancePlan;
import com.xbb.baojing.plan.InsurancePlanMapper;
import com.xbb.baojing.plan.PricingService;
import com.xbb.baojing.position.WorkPosition;
import com.xbb.baojing.position.WorkPositionMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
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
    private final PolicyMemberMapper policyMemberMapper;
    private final InsurancePlanMapper planMapper;
    private final WorkPositionMapper positionMapper;
    private final ActualEmployerMapper actualEmployerMapper;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;

    public ReportsController(PolicyMapper policyMapper, InsuredPersonMapper personMapper, ClaimMapper claimMapper,
                              EnterpriseMapper enterpriseMapper, PolicyPricingService policyPricingService,
                              PolicyMemberMapper policyMemberMapper, InsurancePlanMapper planMapper,
                              WorkPositionMapper positionMapper, ActualEmployerMapper actualEmployerMapper,
                              UserMapper userMapper, ObjectMapper objectMapper) {
        this.policyMapper = policyMapper;
        this.personMapper = personMapper;
        this.claimMapper = claimMapper;
        this.enterpriseMapper = enterpriseMapper;
        this.policyPricingService = policyPricingService;
        this.policyMemberMapper = policyMemberMapper;
        this.planMapper = planMapper;
        this.positionMapper = positionMapper;
        this.actualEmployerMapper = actualEmployerMapper;
        this.userMapper = userMapper;
        this.objectMapper = objectMapper;
    }

    public record ReportRow(String id, String name, String period, double value, String detail) {}

    @GetMapping("/reports")
    public List<ReportRow> reports(User user) {
        Integer scoped = "enterprise".equals(user.getRole()) ? user.getEnterpriseId() : null;
        List<Policy> policies = policyMapper.search(scoped);
        long peopleCount = personMapper.search(scoped, null).size();
        long claimsCount = claimMapper.search(scoped, null).size();

        YearMonth now = YearMonth.now();
        LocalDate monthStart = now.atDay(1);
        LocalDate today = LocalDate.now();
        PremiumDetailReport currentDetail = premiumDetails(monthStart.toString(), today.toString(), null, "", null, user);
        double premium = currentDetail.totalPremium();
        double settlement = currentDetail.totalSettlement();
        double commission = currentDetail.totalCommission();
        String period = now.getYear() + "-" + String.format("%02d", now.getMonthValue()) + "截至" + today.getDayOfMonth() + "日";
        ReportRow premiumRow = new ReportRow("premium", "销售保费汇总", period, PricingService.amount(premium), policies.size() + " 张保单，按日折算并累计至当前日期");
        ReportRow peopleRow = new ReportRow("people", "参保人员报表", "当前", peopleCount, "在册参保人员");
        ReportRow claimsRow = new ReportRow("claims", "理赔统计报表", "累计", claimsCount, "理赔案件");
        List<Enterprise> usageEnterprises = scoped != null ? List.of(enterpriseMapper.findById(scoped)) : enterpriseMapper.search(null, null, null);
        double usageFee = 0;
        for (Enterprise enterprise : usageEnterprises) {
            if (enterprise == null) continue;
            UsageSummary usage = usageSummary(enterprise.getId(), monthStart, today);
            usageFee += usage.personDays() * (enterprise.getUsageFeeDaily() > 0 ? enterprise.getUsageFeeDaily() : 0.1);
        }
        ReportRow usageRow = new ReportRow("usage_fee", "平台使用费", period, PricingService.amount(usageFee), "每人日费率 × 本月有效参保人天");
        if ("enterprise".equals(user.getRole())) return List.of(premiumRow, usageRow, peopleRow, claimsRow);
        return List.of(premiumRow,
                new ReportRow("settlement", "保司结算底价", period, PricingService.amount(settlement), "结算底价按日折算并累计至当前日期"),
                new ReportRow("commission", "总返佣金额", period, PricingService.amount(commission), "返佣单价按日折算并累计至当前日期"),
                usageRow, peopleRow, claimsRow);
    }

    public record PremiumDetailRow(int memberId, int personId, String personName, String idNumber,
                                   String enterpriseName, Integer agentId, String agentName,
                                   String actualEmployerName, String positionName,
                                   String occupationClass, String policyNo, String insurer, String planName,
                                   String billingMode, double unitSalePrice, double unitPolicyFloorPrice,
                                   double unitTotalCommission, double unitAgentCommission, LocalDateTime coverageStart,
                                   LocalDateTime coverageEnd, LocalDate periodStart, LocalDate periodEnd,
                                   long activeDays, double premiumAmount, double settlementAmount,
                                   double commissionAmount, double agentCommissionAmount) {}

    public record PremiumDetailReport(String startDate, String endDate, String asOfDate,
                                      double totalPremium, double totalSettlement, double totalCommission, double totalAgentCommission,
                                      int detailCount, Integer enterpriseId, String insurer, Integer agentId,
                                      List<PremiumDetailRow> rows) {}

    private LocalDate[] parseRange(String startValue, String endValue) {
        try {
            LocalDate start = LocalDate.parse(startValue);
            LocalDate end = LocalDate.parse(endValue);
            if (start.isAfter(end)) throw ApiException.badRequest("开始日期不能晚于结束日期");
            if (ChronoUnit.DAYS.between(start, end) > 730) throw ApiException.badRequest("单次统计时间段不能超过两年");
            return new LocalDate[]{start, end};
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("统计日期格式不正确，应为 yyyy-MM-dd");
        }
    }

    private double periodPremium(double unitPrice, String billingMode, LocalDate start, LocalDate end) {
        if ("daily".equals(billingMode)) return unitPrice * (ChronoUnit.DAYS.between(start, end) + 1);
        double total = 0;
        LocalDate cursor = start;
        while (!cursor.isAfter(end)) {
            YearMonth month = YearMonth.from(cursor);
            LocalDate segmentEnd = end.isBefore(month.atEndOfMonth()) ? end : month.atEndOfMonth();
            long activeDays = ChronoUnit.DAYS.between(cursor, segmentEnd) + 1;
            total += unitPrice * activeDays / month.lengthOfMonth();
            cursor = segmentEnd.plusDays(1);
        }
        return total;
    }

    private record MemberPrices(double salePrice, double policyFloorPrice, double totalCommission, double agentCommission) {}

    private MemberPrices memberPrices(PolicyMember member, Policy policy, InsuredPerson person) {
        try {
            var node = objectMapper.readTree(member.getRateSnapshotJson());
            if (node.has("sale_price") && node.has("policy_floor_price") && node.has("total_commission_amount") && node.has("agent_commission_amount")) {
                return new MemberPrices(node.get("sale_price").asDouble(), node.get("policy_floor_price").asDouble(),
                        node.get("total_commission_amount").asDouble(), node.get("agent_commission_amount").asDouble());
            }
        } catch (Exception ignored) {}
        var pricing = policyPricingService.pricingFor(policy, person);
        return pricing != null
                ? new MemberPrices(pricing.getSalePrice(), pricing.getPolicyFloorPrice(), pricing.getTotalCommissionAmount(), pricing.getAgentCommissionAmount())
                : new MemberPrices(policy.getPremium(), 0, 0, 0);
    }

    @GetMapping("/reports/premium-details")
    public PremiumDetailReport premiumDetails(@RequestParam("start_date") String startValue,
                                              @RequestParam("end_date") String endValue,
                                              @RequestParam(name = "enterprise_id", required = false) Integer enterpriseId,
                                              @RequestParam(name = "insurer", required = false, defaultValue = "") String insurer,
                                              @RequestParam(name = "agent_id", required = false) Integer agentId,
                                              User user) {
        LocalDate[] range = parseRange(startValue, endValue);
        LocalDate start = range[0], end = range[1], asOf = end.isBefore(LocalDate.now()) ? end : LocalDate.now();
        LocalDateTime currentTime = LocalDateTime.now();
        Integer scopedEnterpriseId = enterpriseId;
        if ("enterprise".equals(user.getRole())) {
            if (enterpriseId != null && !enterpriseId.equals(user.getEnterpriseId())) throw ApiException.forbidden("无权查询其他投保单位");
            if (agentId != null) throw ApiException.forbidden("企业端无权按业务员查询佣金");
            scopedEnterpriseId = user.getEnterpriseId();
        } else if (agentId != null) {
            User agent = userMapper.findById(agentId);
            if (agent == null || !"salesperson".equals(agent.getRole())) throw ApiException.notFound("业务员不存在");
        }
        String insurerFilter = insurer == null ? "" : insurer.trim();
        boolean platformView = "admin".equals(user.getRole());
        List<PremiumDetailRow> rows = new ArrayList<>();
        for (PolicyMember member : policyMemberMapper.findAll()) {
            Policy policy = policyMapper.findById(member.getPolicyId());
            if (policy == null || (scopedEnterpriseId != null && !scopedEnterpriseId.equals(policy.getEnterpriseId()))) continue;
            InsuredPerson person = personMapper.findById(member.getPersonId());
            if (person == null) continue;
            InsurancePlan plan = planMapper.findById(policy.getPlanId());
            if (!insurerFilter.isEmpty() && (plan == null || !insurerFilter.equals(plan.getInsurer()))) continue;
            if (member.getEffectiveAt().isAfter(currentTime)) continue;
            LocalDate effective = member.getEffectiveAt().toLocalDate();
            LocalDate terminated = null;
            if (member.getTerminatedAt() != null) {
                terminated = member.getTerminatedAt().toLocalDate();
                if (LocalTime.MIDNIGHT.equals(member.getTerminatedAt().toLocalTime())) terminated = terminated.minusDays(1);
            }
            LocalDate periodStart = effective.isAfter(start) ? effective : start;
            LocalDate periodEnd = terminated != null && terminated.isBefore(asOf) ? terminated : asOf;
            if (periodStart.isAfter(periodEnd)) continue;
            Enterprise enterprise = enterpriseMapper.findById(policy.getEnterpriseId());
            Integer rowAgentId = enterprise != null ? enterprise.getAgentId() : null;
            if (agentId != null && !agentId.equals(rowAgentId)) continue;
            User rowAgent = rowAgentId != null ? userMapper.findById(rowAgentId) : null;
            WorkPosition position = person.getPositionId() != null ? positionMapper.findById(person.getPositionId()) : null;
            ActualEmployer employer = position != null && position.getActualEmployerId() != null ? actualEmployerMapper.findById(position.getActualEmployerId()) : null;
            String billingMode = plan != null ? plan.getBillingMode() : "monthly";
            MemberPrices prices = memberPrices(member, policy, person);
            long activeDays = ChronoUnit.DAYS.between(periodStart, periodEnd) + 1;
            double premium = PricingService.amount(periodPremium(prices.salePrice(), billingMode, periodStart, periodEnd));
            double settlement = PricingService.amount(periodPremium(prices.policyFloorPrice(), billingMode, periodStart, periodEnd));
            double commission = PricingService.amount(periodPremium(prices.totalCommission(), billingMode, periodStart, periodEnd));
            double agentCommission = PricingService.amount(periodPremium(prices.agentCommission(), billingMode, periodStart, periodEnd));
            rows.add(new PremiumDetailRow(member.getId(), person.getId(), person.getName(), person.getIdNumber(),
                    enterprise != null ? enterprise.getName() : "",
                    platformView ? rowAgentId : null, platformView && rowAgent != null ? rowAgent.getName() : "",
                    employer != null ? employer.getName() : (position != null ? position.getActualEmployer() : ""),
                    position != null ? position.getName() : person.getOccupation(), person.getOccupationClass(),
                    policy.getPolicyNo(), plan != null ? plan.getInsurer() : "", plan != null ? plan.getName() : "",
                    billingMode, PricingService.amount(prices.salePrice()), platformView ? PricingService.amount(prices.policyFloorPrice()) : 0,
                    platformView ? PricingService.amount(prices.totalCommission()) : 0, platformView ? PricingService.amount(prices.agentCommission()) : 0,
                    member.getEffectiveAt(), member.getTerminatedAt(), periodStart, periodEnd, activeDays, premium,
                    platformView ? settlement : 0, platformView ? commission : 0, platformView ? agentCommission : 0));
        }
        double totalPremium = PricingService.amount(rows.stream().mapToDouble(PremiumDetailRow::premiumAmount).sum());
        double totalSettlement = platformView ? PricingService.amount(rows.stream().mapToDouble(PremiumDetailRow::settlementAmount).sum()) : 0;
        double totalCommission = platformView ? PricingService.amount(rows.stream().mapToDouble(PremiumDetailRow::commissionAmount).sum()) : 0;
        double totalAgentCommission = platformView ? PricingService.amount(rows.stream().mapToDouble(PremiumDetailRow::agentCommissionAmount).sum()) : 0;
        return new PremiumDetailReport(start.toString(), end.toString(), asOf.toString(), totalPremium, totalSettlement, totalCommission, totalAgentCommission,
                rows.size(), scopedEnterpriseId, insurerFilter, agentId, rows);
    }

    @GetMapping("/reports/premium-details/export")
    public ResponseEntity<byte[]> exportPremiumDetails(@RequestParam("start_date") String startValue,
                                                        @RequestParam("end_date") String endValue,
                                                        @RequestParam(name = "enterprise_id", required = false) Integer enterpriseId,
                                                        @RequestParam(name = "insurer", required = false, defaultValue = "") String insurer,
                                                        @RequestParam(name = "agent_id", required = false) Integer agentId,
                                                        User user) throws IOException {
        PremiumDetailReport report = premiumDetails(startValue, endValue, enterpriseId, insurer, agentId, user);
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("销售保费明细");
            boolean platformExport = "admin".equals(user.getRole());
            List<String> columns = new ArrayList<>(List.of("统计开始", "统计结束", "被保险人", "身份证号", "投保单位", "实际用工单位", "岗位", "职业类别", "保单号", "保险公司", "保险方案", "计费方式", "实际销售价", "本期开始", "本期结束", "计费天数", "保费金额"));
            if (platformExport) {
                columns.add(5, "业务员");
                columns.addAll(List.of("保司结算底价", "保司结算金额", "总返佣单价", "总返佣金额", "业务员佣金单价", "业务员佣金金额"));
            }
            Row header = sheet.createRow(0);
            for (int i = 0; i < columns.size(); i++) header.createCell(i).setCellValue(columns.get(i));
            int rowIndex = 1;
            for (PremiumDetailRow detail : report.rows()) {
                List<Object> values = new ArrayList<>(java.util.Arrays.asList(report.startDate(), report.endDate(), detail.personName(), detail.idNumber(), detail.enterpriseName(), detail.actualEmployerName(), detail.positionName(), detail.occupationClass(), detail.policyNo(), detail.insurer(), detail.planName(), "daily".equals(detail.billingMode()) ? "按天" : "按月", detail.unitSalePrice(), detail.periodStart().toString(), detail.periodEnd().toString(), detail.activeDays(), detail.premiumAmount()));
                if (platformExport) {
                    values.add(5, detail.agentName());
                    values.addAll(List.of(detail.unitPolicyFloorPrice(), detail.settlementAmount(), detail.unitTotalCommission(), detail.commissionAmount(), detail.unitAgentCommission(), detail.agentCommissionAmount()));
                }
                Row row = sheet.createRow(rowIndex++);
                for (int i = 0; i < values.size(); i++) {
                    Object value = values.get(i);
                    if (value instanceof Number number) row.createCell(i).setCellValue(number.doubleValue());
                    else row.createCell(i).setCellValue(String.valueOf(value));
                }
            }
            Row totalRow = sheet.createRow(rowIndex + 1); totalRow.createCell(0).setCellValue("销售保费总额"); totalRow.createCell(1).setCellValue(report.totalPremium());
            if (platformExport) {
                Row settlementRow = sheet.createRow(rowIndex + 2); settlementRow.createCell(0).setCellValue("保司结算总额"); settlementRow.createCell(1).setCellValue(report.totalSettlement());
                Row commissionRow = sheet.createRow(rowIndex + 3); commissionRow.createCell(0).setCellValue("总返佣金额"); commissionRow.createCell(1).setCellValue(report.totalCommission());
                Row agentCommissionRow = sheet.createRow(rowIndex + 4); agentCommissionRow.createCell(0).setCellValue("业务员佣金金额"); agentCommissionRow.createCell(1).setCellValue(report.totalAgentCommission());
            }
            for (int i = 0; i < columns.size(); i++) sheet.autoSizeColumn(i);
            ByteArrayOutputStream output = new ByteArrayOutputStream(); workbook.write(output);
            HttpHeaders headers = new HttpHeaders(); headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=premium-details-" + report.startDate() + "-" + report.endDate() + ".xlsx");
            return ResponseEntity.ok().headers(headers).contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(output.toByteArray());
        }
    }

    private record UsageSummary(long personDays, long activePeople) {}

    private UsageSummary usageSummary(int enterpriseId, LocalDate requestedStart, LocalDate requestedEnd) {
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        LocalDate cutoff = requestedEnd != null && requestedEnd.isBefore(today) ? requestedEnd : today;
        Map<Integer, List<LocalDate[]>> intervals = new HashMap<>();
        for (PolicyMember member : policyMemberMapper.findAll()) {
            Policy policy = policyMapper.findById(member.getPolicyId());
            if (policy == null || policy.getEnterpriseId() != enterpriseId || member.getEffectiveAt().isAfter(now)) continue;
            LocalDate start = requestedStart != null && requestedStart.isAfter(member.getEffectiveAt().toLocalDate()) ? requestedStart : member.getEffectiveAt().toLocalDate();
            LocalDate end = cutoff;
            if (member.getTerminatedAt() != null) {
                LocalDate terminated = member.getTerminatedAt().toLocalDate();
                if (LocalTime.MIDNIGHT.equals(member.getTerminatedAt().toLocalTime())) terminated = terminated.minusDays(1);
                if (terminated.isBefore(end)) end = terminated;
            }
            if (!start.isAfter(end)) intervals.computeIfAbsent(member.getPersonId(), ignored -> new ArrayList<>()).add(new LocalDate[]{start, end});
        }
        long personDays = 0, activePeople = 0;
        for (List<LocalDate[]> personIntervals : intervals.values()) {
            personIntervals.sort(java.util.Comparator.comparing(values -> values[0]));
            List<LocalDate[]> merged = new ArrayList<>();
            for (LocalDate[] interval : personIntervals) {
                if (merged.isEmpty() || interval[0].isAfter(merged.get(merged.size() - 1)[1].plusDays(1))) merged.add(interval.clone());
                else if (interval[1].isAfter(merged.get(merged.size() - 1)[1])) merged.get(merged.size() - 1)[1] = interval[1];
            }
            personDays += merged.stream().mapToLong(values -> ChronoUnit.DAYS.between(values[0], values[1]) + 1).sum();
            if (merged.stream().anyMatch(values -> !today.isBefore(values[0]) && !today.isAfter(values[1]))) activePeople++;
        }
        return new UsageSummary(personDays, activePeople);
    }

    public record BillingRow(int id, String enterpriseName, String account, double balance, String status,
                              double dailyRate, double estimatedDaily, Double monthlyEstimate,
                              long activePeople, long monthPersonDays, double monthAccrued,
                              long totalPersonDays, double totalAccrued, String asOfDate) {}

    @GetMapping("/billing")
    public List<BillingRow> billing(User user) {
        List<Enterprise> enterprises = "enterprise".equals(user.getRole()) && user.getEnterpriseId() != null
                ? List.of(enterpriseMapper.findById(user.getEnterpriseId()))
                : enterpriseMapper.search(null, null, null);
        List<BillingRow> rows = new ArrayList<>();
        for (Enterprise e : enterprises) {
            LocalDate today = LocalDate.now();
            double rate = e.getUsageFeeDaily() > 0 ? e.getUsageFeeDaily() : 0.1;
            UsageSummary month = usageSummary(e.getId(), today.withDayOfMonth(1), today);
            UsageSummary lifetime = usageSummary(e.getId(), null, today);
            double dailyUsage = month.activePeople() * rate;
            rows.add(new BillingRow(e.getId(), e.getName(), "保费账户", e.getPremiumBalance(), "正常", 0, 0, 0.0, 0, 0, 0, 0, 0, today.toString()));
            rows.add(new BillingRow(e.getId(), e.getName(), "平台使用费账户", e.getUsageBalance(), "正常",
                    rate, PricingService.amount(dailyUsage), PricingService.amount(month.personDays() * rate),
                    month.activePeople(), month.personDays(), PricingService.amount(month.personDays() * rate),
                    lifetime.personDays(), PricingService.amount(lifetime.personDays() * rate), today.toString()));
        }
        return rows;
    }
}
