package com.xbb.baojing.insured;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.xbb.baojing.agent.AgentCommission;
import com.xbb.baojing.agent.AgentCommissionMapper;
import com.xbb.baojing.common.ApiException;
import com.xbb.baojing.common.User;
import com.xbb.baojing.enterprise.ActualEmployer;
import com.xbb.baojing.enterprise.ActualEmployerMapper;
import com.xbb.baojing.enterprise.Enterprise;
import com.xbb.baojing.enterprise.EnterpriseMapper;
import com.xbb.baojing.plan.InsurancePlan;
import com.xbb.baojing.plan.InsurancePlanMapper;
import com.xbb.baojing.plan.PricingService;
import com.xbb.baojing.plan.PricingSnapshot;
import com.xbb.baojing.position.WorkPosition;
import com.xbb.baojing.position.WorkPositionMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api")
public class PolicyController {
    private final PolicyMapper policyMapper;
    private final EnterpriseMapper enterpriseMapper;
    private final InsurancePlanMapper planMapper;
    private final AgentCommissionMapper commissionMapper;
    private final InsuredPersonMapper personMapper;
    private final WorkPositionMapper positionMapper;
    private final ActualEmployerMapper actualEmployerMapper;
    private final PricingService pricingService;

    public PolicyController(PolicyMapper policyMapper, EnterpriseMapper enterpriseMapper, InsurancePlanMapper planMapper,
                             AgentCommissionMapper commissionMapper, InsuredPersonMapper personMapper, WorkPositionMapper positionMapper,
                             ActualEmployerMapper actualEmployerMapper, PricingService pricingService) {
        this.policyMapper = policyMapper;
        this.enterpriseMapper = enterpriseMapper;
        this.planMapper = planMapper;
        this.commissionMapper = commissionMapper;
        this.personMapper = personMapper;
        this.positionMapper = positionMapper;
        this.actualEmployerMapper = actualEmployerMapper;
        this.pricingService = pricingService;
    }

    public static class PolicyOut extends Policy {
        private double premiumOriginal, calculatedPremium, insuranceBaseTotal, policyFloorTotal, minimumSaleTotal, saleTotal, totalCommissionTotal, agentCommissionTotal;
        private int insuredCount;
        private String enterpriseName, insurer, planName, billingMode, effectiveMode;
        private PricingSnapshot unit;
        public double getPremiumOriginal() { return premiumOriginal; }
        public void setPremiumOriginal(double v) { this.premiumOriginal = v; }
        public double getCalculatedPremium() { return calculatedPremium; }
        public void setCalculatedPremium(double v) { this.calculatedPremium = v; }
        public double getInsuranceBaseTotal() { return insuranceBaseTotal; }
        public void setInsuranceBaseTotal(double v) { this.insuranceBaseTotal = v; }
        public double getPolicyFloorTotal() { return policyFloorTotal; }
        public void setPolicyFloorTotal(double v) { this.policyFloorTotal = v; }
        public double getMinimumSaleTotal() { return minimumSaleTotal; }
        public void setMinimumSaleTotal(double v) { this.minimumSaleTotal = v; }
        public double getSaleTotal() { return saleTotal; }
        public void setSaleTotal(double v) { this.saleTotal = v; }
        public double getTotalCommissionTotal() { return totalCommissionTotal; }
        public void setTotalCommissionTotal(double v) { this.totalCommissionTotal = v; }
        public double getAgentCommissionTotal() { return agentCommissionTotal; }
        public void setAgentCommissionTotal(double v) { this.agentCommissionTotal = v; }
        public int getInsuredCount() { return insuredCount; }
        public void setInsuredCount(int v) { this.insuredCount = v; }
        public String getEnterpriseName() { return enterpriseName; }
        public void setEnterpriseName(String v) { this.enterpriseName = v; }
        public String getInsurer() { return insurer; }
        public void setInsurer(String v) { this.insurer = v; }
        public String getPlanName() { return planName; }
        public void setPlanName(String v) { this.planName = v; }
        public String getBillingMode() { return billingMode; }
        public void setBillingMode(String v) { this.billingMode = v; }
        public String getEffectiveMode() { return effectiveMode; }
        public void setEffectiveMode(String v) { this.effectiveMode = v; }
        @JsonUnwrapped
        public PricingSnapshot getUnit() { return unit; }
        public void setUnit(PricingSnapshot v) { this.unit = v; }
    }

    /** Ports services/policies.py's policy_dict() 1:1. */
    private PolicyOut policyDict(Policy policy) {
        Enterprise enterprise = enterpriseMapper.findById(policy.getEnterpriseId());
        InsurancePlan plan = planMapper.findById(policy.getPlanId());
        AgentCommission relation = commissionMapper.findActiveRelation(policy.getEnterpriseId(), policy.getPlanId());
        List<InsuredPerson> people = personMapper.findByPolicy(policy.getId());

        List<PricingSnapshot> snapshots = new java.util.ArrayList<>();
        if (plan != null) {
            for (InsuredPerson p : people) snapshots.add(pricingService.snapshot(plan, relation, pricingService.planPriceForClass(plan, p.getOccupationClass())));
            if (snapshots.isEmpty()) snapshots.add(pricingService.snapshot(plan, relation));
        }
        java.util.function.Function<java.util.function.ToDoubleFunction<PricingSnapshot>, Double> total =
                f -> PricingService.amount(snapshots.stream().mapToDouble(f).sum());
        double calculated = !people.isEmpty() ? total.apply(PricingSnapshot::getSalePrice) : policy.getPremium();
        PricingSnapshot unit = snapshots.isEmpty() ? null : snapshots.get(0);

        PolicyOut out = new PolicyOut();
        out.setId(policy.getId());
        out.setPolicyNo(policy.getPolicyNo());
        out.setEnterpriseId(policy.getEnterpriseId());
        out.setPlanId(policy.getPlanId());
        out.setPremium(PricingService.amount(calculated));
        out.setStatus(policy.getStatus());
        out.setStartDate(policy.getStartDate());
        out.setEndDate(policy.getEndDate());
        out.setCreatedAt(policy.getCreatedAt());
        out.setPremiumOriginal(PricingService.amount(policy.getPremium()));
        out.setCalculatedPremium(PricingService.amount(calculated));
        out.setInsuredCount(people.size());
        out.setEnterpriseName(enterprise != null ? enterprise.getName() : "");
        out.setInsurer(plan != null ? plan.getInsurer() : "");
        out.setPlanName(plan != null ? plan.getName() : "");
        out.setBillingMode(plan != null ? plan.getBillingMode() : "monthly");
        out.setEffectiveMode(plan != null ? plan.getEffectiveMode() : "next_day");
        out.setUnit(unit);
        out.setInsuranceBaseTotal(!people.isEmpty() ? total.apply(PricingSnapshot::getInsuranceBasePrice) : (unit != null ? unit.getInsuranceBasePrice() : 0));
        out.setPolicyFloorTotal(!people.isEmpty() ? total.apply(PricingSnapshot::getPolicyFloorPrice) : (unit != null ? unit.getPolicyFloorPrice() : 0));
        out.setMinimumSaleTotal(!people.isEmpty() ? total.apply(PricingSnapshot::getMinimumSalePrice) : (unit != null ? unit.getMinimumSalePrice() : 0));
        out.setSaleTotal(!people.isEmpty() ? total.apply(PricingSnapshot::getSalePrice) : (unit != null ? unit.getSalePrice() : 0));
        out.setTotalCommissionTotal(!people.isEmpty() ? total.apply(PricingSnapshot::getTotalCommissionAmount) : (unit != null ? unit.getTotalCommissionAmount() : 0));
        out.setAgentCommissionTotal(!people.isEmpty() ? total.apply(PricingSnapshot::getAgentCommissionAmount) : (unit != null ? unit.getAgentCommissionAmount() : 0));
        return out;
    }

    @GetMapping("/policies")
    public List<PolicyOut> list(User user) {
        Integer scoped = "enterprise".equals(user.getRole()) && user.getEnterpriseId() != null ? user.getEnterpriseId() : null;
        return policyMapper.search(scoped).stream().map(this::policyDict).toList();
    }

    @GetMapping("/policies/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable int id, User user) throws IOException {
        Policy policy = policyMapper.findById(id);
        if (policy == null) throw ApiException.notFound("保单不存在");
        if ("enterprise".equals(user.getRole()) && !user.getEnterpriseId().equals(policy.getEnterpriseId())) throw ApiException.forbidden("无权导出该保单");
        Enterprise enterprise = enterpriseMapper.findById(policy.getEnterpriseId());
        InsurancePlan plan = planMapper.findById(policy.getPlanId());
        AgentCommission relation = commissionMapper.findActiveRelation(policy.getEnterpriseId(), policy.getPlanId());

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("保单人员明细");
            Row header = sheet.createRow(0);
            boolean enterpriseExport = "enterprise".equals(user.getRole());
            String[] cols = enterpriseExport
                    ? new String[]{"保单号", "投保单位", "实际用工单位", "岗位", "职业类别", "被保险人", "身份证号", "保险公司", "保险方案", "实际销售价", "开始日期", "结束日期", "保单状态"}
                    : new String[]{"保单号", "投保单位", "实际用工单位", "岗位", "职业类别", "被保险人", "身份证号", "保险公司", "保险方案", "保险原价", "总返佣比例", "总返佣金额", "保司结算底价", "平台利润", "销售最低价", "实际销售价", "业务员佣金", "开始日期", "结束日期", "保单状态"};
            for (int i = 0; i < cols.length; i++) header.createCell(i).setCellValue(cols[i]);
            int rowIdx = 1;
            for (InsuredPerson person : personMapper.findByPolicy(id)) {
                WorkPosition position = person.getPositionId() != null ? positionMapper.findById(person.getPositionId()) : null;
                ActualEmployer employer = position != null && position.getActualEmployerId() != null ? actualEmployerMapper.findById(position.getActualEmployerId()) : null;
                PricingSnapshot pricing = plan != null ? pricingService.snapshot(plan, relation, pricingService.planPriceForClass(plan, person.getOccupationClass())) : null;
                Row row = sheet.createRow(rowIdx++);
                Object[] prefix = {policy.getPolicyNo(), enterprise != null ? enterprise.getName() : "",
                        employer != null ? employer.getName() : (position != null ? position.getActualEmployer() : ""),
                        position != null ? position.getName() : person.getOccupation(), person.getOccupationClass(), person.getName(), person.getIdNumber(),
                        plan != null ? plan.getInsurer() : "", plan != null ? plan.getName() : ""};
                java.util.List<Object> valueList = new java.util.ArrayList<>(java.util.Arrays.asList(prefix));
                if (enterpriseExport) {
                    valueList.add(pricing != null ? pricing.getSalePrice() : 0);
                } else {
                    valueList.addAll(java.util.List.of(pricing != null ? pricing.getInsuranceBasePrice() : 0, pricing != null ? pricing.getTotalCommissionRate() : 0,
                            pricing != null ? pricing.getTotalCommissionAmount() : 0, pricing != null ? pricing.getPolicyFloorPrice() : 0,
                            pricing != null ? pricing.getProfitAmount() : 0, pricing != null ? pricing.getMinimumSalePrice() : 0,
                            pricing != null ? pricing.getSalePrice() : 0, pricing != null ? pricing.getAgentCommissionAmount() : 0));
                }
                valueList.addAll(java.util.List.of(policy.getStartDate(), policy.getEndDate(), policy.getStatus()));
                Object[] values = valueList.toArray();
                for (int i = 0; i < values.length; i++) {
                    Object v = values[i];
                    if (v instanceof Number n) row.createCell(i).setCellValue(n.doubleValue());
                    else row.createCell(i).setCellValue(String.valueOf(v));
                }
            }
            for (int i = 0; i < cols.length; i++) sheet.autoSizeColumn(i);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=policy-" + policy.getPolicyNo() + ".xlsx");
            return ResponseEntity.ok().headers(headers).contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(out.toByteArray());
        }
    }
}
