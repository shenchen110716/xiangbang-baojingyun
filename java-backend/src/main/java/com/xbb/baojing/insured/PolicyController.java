package com.xbb.baojing.insured;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbb.baojing.agent.AgentCommission;
import com.xbb.baojing.agent.AgentCommissionMapper;
import com.xbb.baojing.common.ApiException;
import com.xbb.baojing.common.AppProperties;
import com.xbb.baojing.common.AuditService;
import com.xbb.baojing.common.FileTokenService;
import com.xbb.baojing.common.InternalPricingFilter;
import com.xbb.baojing.common.Rbac;
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
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.List;
import java.util.Set;

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
    private final PolicyPricingService policyPricingService;
    private final AuditService auditService;
    private final FileTokenService fileTokenService;
    private final String uploadsDir;
    private final ObjectMapper objectMapper;
    private static final SecureRandom RANDOM = new SecureRandom();

    public PolicyController(PolicyMapper policyMapper, EnterpriseMapper enterpriseMapper, InsurancePlanMapper planMapper,
                             AgentCommissionMapper commissionMapper, InsuredPersonMapper personMapper, WorkPositionMapper positionMapper,
                             ActualEmployerMapper actualEmployerMapper, PricingService pricingService, PolicyPricingService policyPricingService,
                             AuditService auditService, FileTokenService fileTokenService, AppProperties props, ObjectMapper objectMapper) {
        this.policyMapper = policyMapper;
        this.enterpriseMapper = enterpriseMapper;
        this.planMapper = planMapper;
        this.commissionMapper = commissionMapper;
        this.personMapper = personMapper;
        this.positionMapper = positionMapper;
        this.actualEmployerMapper = actualEmployerMapper;
        this.pricingService = pricingService;
        this.policyPricingService = policyPricingService;
        this.auditService = auditService;
        this.fileTokenService = fileTokenService;
        this.uploadsDir = props.getUploadsDir();
        this.objectMapper = objectMapper;
    }

    private String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    public static class PolicyOut extends Policy {
        private double premiumOriginal, calculatedPremium, insuranceBaseTotal, policyFloorTotal, minimumSaleTotal, saleTotal, totalCommissionTotal, agentCommissionTotal;
        private int insuredCount;
        private String enterpriseName, insurer, planName, billingMode, effectiveMode, documentDownloadUrl;
        private PricingSnapshot unit;
        public String getDocumentDownloadUrl() { return documentDownloadUrl; }
        public void setDocumentDownloadUrl(String v) { this.documentDownloadUrl = v; }
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

    /** Ports services/policies.py's policy_dict(). Prices/counts reflect the
     * current calendar month's day-prorated roster (PolicyPricingService),
     * not just currently-active people — someone terminated partway through
     * the month still owes premium for their billable days (feedback item 8). */
    private PolicyOut policyDict(Policy policy) {
        Enterprise enterprise = enterpriseMapper.findById(policy.getEnterpriseId());
        InsurancePlan plan = planMapper.findById(policy.getPlanId());
        PolicyPricingService.CurrentMonthBilling billing = policyPricingService.currentMonthBilling(policy);
        List<PolicyPricingService.BilledRow> rows = billing.rows();

        java.util.function.Function<java.util.function.ToDoubleFunction<PricingSnapshot>, Double> total =
                f -> PricingService.amount(rows.stream().mapToDouble(r -> f.applyAsDouble(r.snapshot()) * r.ratio()).sum());
        double calculated = !rows.isEmpty() ? total.apply(PricingSnapshot::getSalePrice) : policy.getPremium();
        PricingSnapshot unit = !rows.isEmpty() ? rows.get(0).snapshot() : (plan != null ? pricingService.snapshot(plan, commissionMapper.findActiveRelation(policy.getEnterpriseId(), policy.getPlanId())) : null);

        PolicyOut out = new PolicyOut();
        out.setId(policy.getId());
        out.setPolicyNo(policy.getPolicyNo());
        out.setEnterpriseId(policy.getEnterpriseId());
        out.setPlanId(policy.getPlanId());
        out.setPremium(PricingService.amount(calculated));
        out.setStatus(policy.getStatus());
        out.setStartDate(policy.getStartDate());
        out.setEndDate(policy.getEndDate());
        out.setDocumentUrl(policy.getDocumentUrl());
        out.setDocumentName(policy.getDocumentName());
        if (policy.getDocumentUrl() != null && !policy.getDocumentUrl().isBlank()) {
            FileTokenService.Token token = fileTokenService.makeToken("policy-document:" + policy.getId());
            out.setDocumentDownloadUrl("/api/policies/" + policy.getId() + "/document/download?token=" + token.token() + "&expires=" + token.expires());
        }
        out.setCreatedAt(policy.getCreatedAt());
        out.setPremiumOriginal(PricingService.amount(policy.getPremium()));
        out.setCalculatedPremium(PricingService.amount(calculated));
        out.setInsuredCount(billing.personCount());
        out.setEnterpriseName(enterprise != null ? enterprise.getName() : "");
        out.setInsurer(plan != null ? plan.getInsurer() : "");
        out.setPlanName(plan != null ? plan.getName() : "");
        out.setBillingMode(plan != null ? plan.getBillingMode() : "monthly");
        out.setEffectiveMode(plan != null ? plan.getEffectiveMode() : "next_day");
        out.setUnit(unit);
        out.setInsuranceBaseTotal(!rows.isEmpty() ? total.apply(PricingSnapshot::getInsuranceBasePrice) : (unit != null ? unit.getInsuranceBasePrice() : 0));
        out.setPolicyFloorTotal(!rows.isEmpty() ? total.apply(PricingSnapshot::getPolicyFloorPrice) : (unit != null ? unit.getPolicyFloorPrice() : 0));
        out.setMinimumSaleTotal(!rows.isEmpty() ? total.apply(PricingSnapshot::getMinimumSalePrice) : (unit != null ? unit.getMinimumSalePrice() : 0));
        out.setSaleTotal(!rows.isEmpty() ? total.apply(PricingSnapshot::getSalePrice) : (unit != null ? unit.getSalePrice() : 0));
        out.setTotalCommissionTotal(!rows.isEmpty() ? total.apply(PricingSnapshot::getTotalCommissionAmount) : (unit != null ? unit.getTotalCommissionAmount() : 0));
        out.setAgentCommissionTotal(!rows.isEmpty() ? total.apply(PricingSnapshot::getAgentCommissionAmount) : (unit != null ? unit.getAgentCommissionAmount() : 0));
        return out;
    }

    @GetMapping("/policies")
    public List<Object> list(User user) {
        Integer scoped = "enterprise".equals(user.getRole()) && user.getEnterpriseId() != null ? user.getEnterpriseId() : null;
        return policyMapper.search(scoped).stream().map(p -> InternalPricingFilter.strip(policyDict(p), user, objectMapper)).toList();
    }

    @PostMapping("/policies/{id}/document/upload")
    public PolicyOut uploadDocument(@PathVariable int id, @RequestParam MultipartFile file, User user) throws IOException {
        Rbac.requireRole(user, "仅平台端可导入保单文件", "admin");
        Policy policy = policyMapper.findById(id);
        if (policy == null) throw ApiException.notFound("保单不存在");
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String suffix = original.contains(".") ? original.substring(original.lastIndexOf('.')).toLowerCase() : "";
        if (!Set.of(".pdf", ".jpg", ".jpeg", ".png").contains(suffix)) throw ApiException.badRequest("仅支持 PDF 或图片格式");
        if (file.getSize() > 20L * 1024 * 1024) throw ApiException.badRequest("文件不能超过 20MB");
        Path folder = Paths.get(uploadsDir, "policies", String.valueOf(id));
        Files.createDirectories(folder);
        String stored = randomHex(8) + suffix;
        Files.write(folder.resolve(stored), file.getBytes());
        policy.setDocumentUrl("/uploads/policies/" + id + "/" + stored);
        policy.setDocumentName(original.isBlank() ? stored : original);
        policyMapper.updateDocument(policy);
        auditService.log(user, "upload", "policy_document", String.valueOf(id));
        return policyDict(policy);
    }

    @GetMapping("/policies/{id}/document/download")
    public ResponseEntity<org.springframework.core.io.Resource> downloadDocument(
            @PathVariable int id, @RequestParam String token, @RequestParam long expires) throws IOException {
        if (!fileTokenService.verify("policy-document:" + id, expires, token)) throw ApiException.forbidden("下载链接无效或已过期");
        Policy policy = policyMapper.findById(id);
        if (policy == null || policy.getDocumentUrl() == null || policy.getDocumentUrl().isBlank()) throw ApiException.notFound("保单文件不存在");
        Path path = Paths.get(".", policy.getDocumentUrl());
        if (!Files.isRegularFile(path)) throw ApiException.notFound("文件不存在");
        return ResponseEntity.ok().body(new org.springframework.core.io.UrlResource(path.toUri()));
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
