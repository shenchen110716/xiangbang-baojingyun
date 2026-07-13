package com.xbb.baojing.enrollment;

import com.xbb.baojing.agent.AgentCommission;
import com.xbb.baojing.agent.AgentCommissionMapper;
import com.xbb.baojing.common.ApiException;
import com.xbb.baojing.common.AuditService;
import com.xbb.baojing.common.Providers;
import com.xbb.baojing.common.User;
import com.xbb.baojing.enterprise.Enterprise;
import com.xbb.baojing.enterprise.EnterpriseMapper;
import com.xbb.baojing.insured.InsuredPerson;
import com.xbb.baojing.insured.InsuredPersonMapper;
import com.xbb.baojing.insured.PolicyMember;
import com.xbb.baojing.insured.PolicyMemberMapper;
import com.xbb.baojing.plan.InsurancePlan;
import com.xbb.baojing.plan.InsurancePlanMapper;
import com.xbb.baojing.plan.PricingService;
import com.xbb.baojing.plan.PricingSnapshot;
import com.xbb.baojing.position.WorkPosition;
import com.xbb.baojing.position.WorkPositionMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api")
public class EnrollmentController {
    private final EnterpriseMapper enterpriseMapper;
    private final InsuredPersonMapper personMapper;
    private final WorkPositionMapper positionMapper;
    private final InsurancePlanMapper planMapper;
    private final AgentCommissionMapper commissionMapper;
    private final EnrollmentEmailMapper emailMapper;
    private final PolicyMemberMapper policyMemberMapper;
    private final PricingService pricingService;
    private final Providers providers;
    private final AuditService auditService;

    public EnrollmentController(EnterpriseMapper enterpriseMapper, InsuredPersonMapper personMapper, WorkPositionMapper positionMapper,
                                 InsurancePlanMapper planMapper, AgentCommissionMapper commissionMapper, EnrollmentEmailMapper emailMapper,
                                 PolicyMemberMapper policyMemberMapper, PricingService pricingService, Providers providers, AuditService auditService) {
        this.enterpriseMapper = enterpriseMapper;
        this.personMapper = personMapper;
        this.positionMapper = positionMapper;
        this.planMapper = planMapper;
        this.commissionMapper = commissionMapper;
        this.emailMapper = emailMapper;
        this.policyMemberMapper = policyMemberMapper;
        this.pricingService = pricingService;
        this.providers = providers;
        this.auditService = auditService;
    }

    private static final String[] CSV_HEADER = {"投保单位", "实际工作单位", "岗位", "姓名", "身份证号", "手机号", "职业类别", "保险原价",
            "保司结算底价", "平台利润", "销售最低价", "实际销售价", "总返佣金额", "业务员佣金", "状态", "日期", "添加时间", "生效时间", "停保时间"};

    private static final java.time.format.DateTimeFormatter DATE_TIME_FORMAT = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private String formatDateTime(LocalDateTime v) {
        return v == null ? "" : v.format(DATE_TIME_FORMAT);
    }

    private List<InsuredPerson> matchingPeople(Integer enterpriseId, Integer planId, String kind, String targetDate) {
        List<InsuredPerson> all = personMapper.search(enterpriseId, null);
        List<InsuredPerson> filtered = new ArrayList<>();
        for (InsuredPerson p : all) {
            if (planId != null) {
                WorkPosition pos = p.getPositionId() != null ? positionMapper.findById(p.getPositionId()) : null;
                if (pos == null || !Objects.equals(pos.getPlanId(), planId)) continue;
            }
            if ("termination".equals(kind)) {
                if (!"stopped".equals(p.getStatus())) continue;
            } else {
                boolean createdOnDate = p.getCreatedAt() != null && p.getCreatedAt().toLocalDate().toString().equals(targetDate);
                if (!createdOnDate || !Set.of("active", "pending").contains(p.getStatus())) continue;
            }
            filtered.add(p);
        }
        return filtered;
    }

    private String[] rowFor(InsuredPerson p, Enterprise enterprise, String targetDate) {
        WorkPosition position = p.getPositionId() != null ? positionMapper.findById(p.getPositionId()) : null;
        InsurancePlan plan = position != null && position.getPlanId() != null ? planMapper.findById(position.getPlanId()) : null;
        AgentCommission relation = plan != null ? commissionMapper.findActiveRelation(p.getEnterpriseId(), plan.getId()) : null;
        PricingSnapshot pricing = plan != null ? pricingService.snapshot(plan, relation, pricingService.planPriceForClass(plan, p.getOccupationClass())) : null;
        PolicyMember latestMember = policyMemberMapper.findLatestForPerson(p.getId());
        return new String[]{
                enterprise != null ? enterprise.getName() : "",
                position != null ? position.getActualEmployer() : "",
                position != null ? position.getName() : p.getOccupation(),
                p.getName(), p.getIdNumber(), p.getPhone() == null ? "" : p.getPhone(), p.getOccupationClass(),
                pricing != null ? String.valueOf(pricing.getInsuranceBasePrice()) : "0",
                pricing != null ? String.valueOf(pricing.getPolicyFloorPrice()) : "0",
                pricing != null ? String.valueOf(pricing.getProfitAmount()) : "0",
                pricing != null ? String.valueOf(pricing.getMinimumSalePrice()) : "0",
                pricing != null ? String.valueOf(pricing.getSalePrice()) : "0",
                pricing != null ? String.valueOf(pricing.getTotalCommissionAmount()) : "0",
                pricing != null ? String.valueOf(pricing.getAgentCommissionAmount()) : "0",
                p.getStatus(), targetDate,
                formatDateTime(p.getCreatedAt()),
                latestMember != null ? formatDateTime(latestMember.getEffectiveAt()) : "",
                latestMember != null ? formatDateTime(latestMember.getTerminatedAt()) : ""};
    }

    private String toCsv(List<String[]> rows) {
        StringBuilder sb = new StringBuilder("﻿");
        sb.append(String.join(",", CSV_HEADER)).append("\n");
        for (String[] row : rows) {
            sb.append(String.join(",", Arrays.stream(row).map(v -> "\"" + v.replace("\"", "\"\"") + "\"").toArray(String[]::new))).append("\n");
        }
        return sb.toString();
    }

    @GetMapping("/enrollment/export")
    public ResponseEntity<byte[]> export(@RequestParam String kind, @RequestParam(name = "date", defaultValue = "") String date,
                                          @RequestParam(name = "plan_id", required = false) Integer planId, User user) {
        String targetDate = date.isBlank() ? LocalDate.now().toString() : date;
        Integer scoped = "enterprise".equals(user.getRole()) && user.getEnterpriseId() != null ? user.getEnterpriseId() : null;
        List<InsuredPerson> people = matchingPeople(scoped, planId, kind, targetDate);
        List<String[]> rows = new ArrayList<>();
        for (InsuredPerson p : people) rows.add(rowFor(p, enterpriseMapper.findById(p.getEnterpriseId()), targetDate));
        byte[] body = toCsv(rows).getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + kind + "-" + targetDate + ".csv");
        return ResponseEntity.ok().headers(headers).contentType(MediaType.parseMediaType("text/csv")).body(body);
    }

    public record SummaryRow(int planId, String insurer, String insurerEmail, String product, long insuredCount, long newCount, long stopCount) {}

    @GetMapping("/enrollment/summary")
    public List<SummaryRow> summary(@RequestParam(name = "date", defaultValue = "") String date, User user) {
        String target = date.isBlank() ? LocalDate.now().toString() : date;
        Integer scoped = "enterprise".equals(user.getRole()) ? user.getEnterpriseId() : null;
        List<SummaryRow> result = new ArrayList<>();
        for (InsurancePlan plan : planMapper.findAll()) {
            List<InsuredPerson> people = personMapper.search(scoped, null).stream()
                    .filter(p -> { WorkPosition pos = p.getPositionId() != null ? positionMapper.findById(p.getPositionId()) : null; return pos != null && Objects.equals(pos.getPlanId(), plan.getId()); })
                    .toList();
            long newCount = people.stream().filter(p -> p.getCreatedAt() != null && p.getCreatedAt().toLocalDate().toString().equals(target) && !"stopped".equals(p.getStatus())).count();
            long stopCount = people.stream().filter(p -> p.getCreatedAt() != null && p.getCreatedAt().toLocalDate().toString().equals(target) && "stopped".equals(p.getStatus())).count();
            long insuredCount = people.stream().filter(p -> !"stopped".equals(p.getStatus())).count();
            result.add(new SummaryRow(plan.getId(), plan.getInsurer(), plan.getInsurerEmail(), plan.getName(), insuredCount, newCount, stopCount));
        }
        return result;
    }

    @PostMapping("/enrollment/send")
    public Map<String, Object> send(@RequestParam("enterprise_id") int enterpriseId, @RequestParam("plan_id") int planId,
                                     @RequestParam(defaultValue = "enrollment") String kind, User user) {
        if ("enterprise".equals(user.getRole()) && !user.getEnterpriseId().equals(enterpriseId)) throw ApiException.forbidden("无权发送该单位名单");
        Enterprise enterprise = enterpriseMapper.findById(enterpriseId);
        InsurancePlan plan = planMapper.findById(planId);
        if (enterprise == null || plan == null) throw ApiException.notFound("投保单位或方案不存在");
        List<InsuredPerson> people = matchingPeople(enterpriseId, planId, kind, LocalDate.now().toString());
        Map<String, Object> payload = Map.of("enterprise", Map.of("id", enterprise.getId(), "name", enterprise.getName()), "people_count", people.size());
        var result = "enrollment".equals(kind) ? providers.insurerProvider(plan.getInsurer()).submitEnrollment(payload) : providers.insurerProvider(plan.getInsurer()).submitTermination(payload);
        auditService.log(user, "send", kind, String.valueOf(enterpriseId), result.requestId());
        return Map.of("ok", result.ok(), "kind", kind, "request_id", result.requestId(), "accepted", result.data().getOrDefault("accepted", 0), "message", result.message());
    }

    @PostMapping("/enrollment/email")
    public Map<String, Object> email(@RequestParam("enterprise_id") int enterpriseId, @RequestParam("plan_id") int planId,
                                      @RequestParam(defaultValue = "enrollment") String kind,
                                      @RequestParam(name = "date", defaultValue = "") String date, User user) {
        if ("enterprise".equals(user.getRole()) && !user.getEnterpriseId().equals(enterpriseId)) throw ApiException.forbidden("无权发送该单位名单");
        Enterprise enterprise = enterpriseMapper.findById(enterpriseId);
        InsurancePlan plan = planMapper.findById(planId);
        if (enterprise == null || plan == null) throw ApiException.notFound("投保单位或产品不存在");
        if (plan.getInsurerEmail() == null || plan.getInsurerEmail().isBlank()) throw ApiException.badRequest("该保险公司方案尚未配置接收邮箱");
        String targetDate = date.isBlank() ? LocalDate.now().toString() : date;
        List<InsuredPerson> people = personMapper.search(enterpriseId, null).stream()
                .filter(p -> { WorkPosition pos = p.getPositionId() != null ? positionMapper.findById(p.getPositionId()) : null; return pos != null && Objects.equals(pos.getPlanId(), planId); })
                .filter(p -> "termination".equals(kind) ? "stopped".equals(p.getStatus()) : (p.getCreatedAt() != null && p.getCreatedAt().toLocalDate().toString().equals(targetDate) && Set.of("active", "pending").contains(p.getStatus())))
                .toList();
        List<String[]> rows = new ArrayList<>();
        for (InsuredPerson p : people) rows.add(rowFor(p, enterprise, targetDate));
        String filename = kind + "-" + targetDate + ".csv";
        String encoded = Base64.getEncoder().encodeToString(toCsv(rows).getBytes(StandardCharsets.UTF_8));
        String subject = plan.getInsurer() + " " + plan.getName() + " " + ("enrollment".equals(kind) ? "新参" : "停保") + "名单 " + targetDate;
        String body = "投保单位：" + enterprise.getName() + "\n业务类型：" + ("enrollment".equals(kind) ? "新参" : "停保") + "\n人数：" + rows.size() + "\n请查收附件名单。";
        var result = providers.emailProvider().sendEmail(plan.getInsurerEmail(), subject, body, List.of(Map.of("filename", filename, "content_base64", encoded, "content_type", "text/csv")));
        EnrollmentEmail record = new EnrollmentEmail();
        record.setEnterpriseId(enterpriseId);
        record.setPlanId(planId);
        record.setKind(kind);
        record.setRecipient(plan.getInsurerEmail());
        record.setFilename(filename);
        record.setPeopleCount(rows.size());
        record.setRequestId(result.requestId());
        record.setStatus(result.ok() ? "sent" : "failed");
        record.setCreatedAt(LocalDateTime.now());
        emailMapper.insert(record);
        auditService.log(user, "send_email", kind, String.valueOf(enterpriseId), result.requestId() + ";count=" + rows.size() + ";to=" + plan.getInsurerEmail());
        return Map.of("ok", result.ok(), "email", plan.getInsurerEmail(), "request_id", result.requestId(), "message", result.message(), "people_count", rows.size(), "filename", filename, "kind", kind);
    }

    @GetMapping("/enrollment/emails")
    public List<EnrollmentEmail> emails(User user) {
        Integer scoped = "enterprise".equals(user.getRole()) ? user.getEnterpriseId() : null;
        List<EnrollmentEmail> rows = emailMapper.search(scoped);
        for (EnrollmentEmail e : rows) {
            Enterprise ent = enterpriseMapper.findById(e.getEnterpriseId());
            InsurancePlan plan = planMapper.findById(e.getPlanId());
            e.setEnterpriseName(ent != null ? ent.getName() : "");
            e.setPlanName(plan != null ? plan.getName() : "");
            e.setInsurer(plan != null ? plan.getInsurer() : "");
        }
        return rows;
    }
}
