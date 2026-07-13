package com.xbb.baojing.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbb.baojing.claim.ClaimMapper;
import com.xbb.baojing.common.ApiException;
import com.xbb.baojing.common.User;
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
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

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
    private final ObjectMapper objectMapper;

    public ReportsController(PolicyMapper policyMapper, InsuredPersonMapper personMapper, ClaimMapper claimMapper,
                              EnterpriseMapper enterpriseMapper, PolicyPricingService policyPricingService,
                              PolicyMemberMapper policyMemberMapper, InsurancePlanMapper planMapper,
                              WorkPositionMapper positionMapper, ActualEmployerMapper actualEmployerMapper,
                              ObjectMapper objectMapper) {
        this.policyMapper = policyMapper;
        this.personMapper = personMapper;
        this.claimMapper = claimMapper;
        this.enterpriseMapper = enterpriseMapper;
        this.policyPricingService = policyPricingService;
        this.policyMemberMapper = policyMemberMapper;
        this.planMapper = planMapper;
        this.positionMapper = positionMapper;
        this.actualEmployerMapper = actualEmployerMapper;
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
        LocalDate monthEnd = now.atEndOfMonth();

        double settlement = 0, commission = 0;
        for (Policy p : policies) {
            var totals = policyPricingService.totals(p);
            settlement += totals.policyFloor();
            commission += totals.totalCommission();
        }
        double premium = premiumDetails(monthStart.toString(), monthEnd.toString(), user).totalPremium();
        String period = now.getYear() + "-" + String.format("%02d", now.getMonthValue()) + "按实际天数";
        ReportRow premiumRow = new ReportRow("premium", "销售保费汇总", period, PricingService.amount(premium), policies.size() + " 张保单，统一按销售价格计算");
        ReportRow peopleRow = new ReportRow("people", "参保人员报表", "当前", peopleCount, "在册参保人员");
        ReportRow claimsRow = new ReportRow("claims", "理赔统计报表", "累计", claimsCount, "理赔案件");
        if ("enterprise".equals(user.getRole())) return List.of(premiumRow, peopleRow, claimsRow);
        return List.of(premiumRow,
                new ReportRow("settlement", "保司结算底价", "当前", PricingService.amount(settlement), "保险原价 ×（1-总返佣比例）"),
                new ReportRow("commission", "总返佣金额", "当前", PricingService.amount(commission), "保险原价 × 总返佣比例"),
                peopleRow, claimsRow);
    }

    public record PremiumDetailRow(int memberId, int personId, String personName, String idNumber,
                                   String enterpriseName, String actualEmployerName, String positionName,
                                   String occupationClass, String policyNo, String insurer, String planName,
                                   String billingMode, double unitSalePrice, LocalDateTime coverageStart,
                                   LocalDateTime coverageEnd, LocalDate periodStart, LocalDate periodEnd,
                                   long activeDays, double premiumAmount) {}

    public record PremiumDetailReport(String startDate, String endDate, double totalPremium,
                                      int detailCount, List<PremiumDetailRow> rows) {}

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

    private double memberSalePrice(PolicyMember member, Policy policy, InsuredPerson person) {
        try {
            var node = objectMapper.readTree(member.getRateSnapshotJson());
            if (node.has("sale_price")) return node.get("sale_price").asDouble();
        } catch (Exception ignored) {}
        return policyPricingService.salePriceFor(policy, person);
    }

    @GetMapping("/reports/premium-details")
    public PremiumDetailReport premiumDetails(@RequestParam("start_date") String startValue,
                                              @RequestParam("end_date") String endValue, User user) {
        LocalDate[] range = parseRange(startValue, endValue);
        LocalDate start = range[0], end = range[1];
        List<PremiumDetailRow> rows = new ArrayList<>();
        for (PolicyMember member : policyMemberMapper.findAll()) {
            Policy policy = policyMapper.findById(member.getPolicyId());
            if (policy == null || ("enterprise".equals(user.getRole()) && !user.getEnterpriseId().equals(policy.getEnterpriseId()))) continue;
            InsuredPerson person = personMapper.findById(member.getPersonId());
            if (person == null) continue;
            LocalDate effective = member.getEffectiveAt().toLocalDate();
            LocalDate terminated = member.getTerminatedAt() != null ? member.getTerminatedAt().toLocalDate() : null;
            LocalDate periodStart = effective.isAfter(start) ? effective : start;
            LocalDate periodEnd = terminated != null && terminated.isBefore(end) ? terminated : end;
            if (periodStart.isAfter(periodEnd)) continue;
            Enterprise enterprise = enterpriseMapper.findById(policy.getEnterpriseId());
            InsurancePlan plan = planMapper.findById(policy.getPlanId());
            WorkPosition position = person.getPositionId() != null ? positionMapper.findById(person.getPositionId()) : null;
            ActualEmployer employer = position != null && position.getActualEmployerId() != null ? actualEmployerMapper.findById(position.getActualEmployerId()) : null;
            String billingMode = plan != null ? plan.getBillingMode() : "monthly";
            double unitPrice = memberSalePrice(member, policy, person);
            long activeDays = ChronoUnit.DAYS.between(periodStart, periodEnd) + 1;
            double premium = PricingService.amount(periodPremium(unitPrice, billingMode, periodStart, periodEnd));
            rows.add(new PremiumDetailRow(member.getId(), person.getId(), person.getName(), person.getIdNumber(),
                    enterprise != null ? enterprise.getName() : "",
                    employer != null ? employer.getName() : (position != null ? position.getActualEmployer() : ""),
                    position != null ? position.getName() : person.getOccupation(), person.getOccupationClass(),
                    policy.getPolicyNo(), plan != null ? plan.getInsurer() : "", plan != null ? plan.getName() : "",
                    billingMode, PricingService.amount(unitPrice), member.getEffectiveAt(), member.getTerminatedAt(),
                    periodStart, periodEnd, activeDays, premium));
        }
        double total = PricingService.amount(rows.stream().mapToDouble(PremiumDetailRow::premiumAmount).sum());
        return new PremiumDetailReport(start.toString(), end.toString(), total, rows.size(), rows);
    }

    @GetMapping("/reports/premium-details/export")
    public ResponseEntity<byte[]> exportPremiumDetails(@RequestParam("start_date") String startValue,
                                                        @RequestParam("end_date") String endValue,
                                                        User user) throws IOException {
        PremiumDetailReport report = premiumDetails(startValue, endValue, user);
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("销售保费明细");
            String[] columns = {"统计开始", "统计结束", "被保险人", "身份证号", "投保单位", "实际用工单位", "岗位", "职业类别", "保单号", "保险公司", "保险方案", "计费方式", "实际销售价", "本期开始", "本期结束", "计费天数", "保费金额"};
            Row header = sheet.createRow(0);
            for (int i = 0; i < columns.length; i++) header.createCell(i).setCellValue(columns[i]);
            int rowIndex = 1;
            for (PremiumDetailRow detail : report.rows()) {
                Object[] values = {report.startDate(), report.endDate(), detail.personName(), detail.idNumber(), detail.enterpriseName(), detail.actualEmployerName(), detail.positionName(), detail.occupationClass(), detail.policyNo(), detail.insurer(), detail.planName(), "daily".equals(detail.billingMode()) ? "按天" : "按月", detail.unitSalePrice(), detail.periodStart().toString(), detail.periodEnd().toString(), detail.activeDays(), detail.premiumAmount()};
                Row row = sheet.createRow(rowIndex++);
                for (int i = 0; i < values.length; i++) {
                    Object value = values[i];
                    if (value instanceof Number number) row.createCell(i).setCellValue(number.doubleValue());
                    else row.createCell(i).setCellValue(String.valueOf(value));
                }
            }
            Row totalRow = sheet.createRow(rowIndex + 1); totalRow.createCell(0).setCellValue("保费总额"); totalRow.createCell(1).setCellValue(report.totalPremium());
            for (int i = 0; i < columns.length; i++) sheet.autoSizeColumn(i);
            ByteArrayOutputStream output = new ByteArrayOutputStream(); workbook.write(output);
            HttpHeaders headers = new HttpHeaders(); headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=premium-details-" + report.startDate() + "-" + report.endDate() + ".xlsx");
            return ResponseEntity.ok().headers(headers).contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(output.toByteArray());
        }
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
