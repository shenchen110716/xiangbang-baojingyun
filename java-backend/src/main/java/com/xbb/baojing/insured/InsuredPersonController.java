package com.xbb.baojing.insured;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbb.baojing.agent.AgentCommission;
import com.xbb.baojing.agent.AgentCommissionMapper;
import com.xbb.baojing.common.ApiException;
import com.xbb.baojing.common.AuditService;
import com.xbb.baojing.common.IdNumberValidator;
import com.xbb.baojing.common.InternalPricingFilter;
import com.xbb.baojing.common.User;
import com.xbb.baojing.enterprise.ActualEmployer;
import com.xbb.baojing.enterprise.ActualEmployerMapper;
import com.xbb.baojing.enterprise.Enterprise;
import com.xbb.baojing.enterprise.EnterpriseMapper;
import com.xbb.baojing.plan.InsurancePlan;
import com.xbb.baojing.plan.InsurancePlanMapper;
import com.xbb.baojing.plan.PricingService;
import com.xbb.baojing.position.WorkPosition;
import com.xbb.baojing.position.WorkPositionMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api")
public class InsuredPersonController {
    private final InsuredPersonMapper personMapper;
    private final EnterpriseMapper enterpriseMapper;
    private final WorkPositionMapper positionMapper;
    private final ActualEmployerMapper actualEmployerMapper;
    private final InsurancePlanMapper planMapper;
    private final PolicyMapper policyMapper;
    private final PolicyMemberMapper policyMemberMapper;
    private final AgentCommissionMapper commissionMapper;
    private final PricingService pricingService;
    private final PolicyMemberService policyMemberService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public InsuredPersonController(InsuredPersonMapper personMapper, EnterpriseMapper enterpriseMapper, WorkPositionMapper positionMapper,
                                    ActualEmployerMapper actualEmployerMapper, InsurancePlanMapper planMapper, PolicyMapper policyMapper,
                                    PolicyMemberMapper policyMemberMapper, AgentCommissionMapper commissionMapper,
                                    PricingService pricingService, PolicyMemberService policyMemberService, AuditService auditService,
                                    ObjectMapper objectMapper) {
        this.personMapper = personMapper;
        this.enterpriseMapper = enterpriseMapper;
        this.positionMapper = positionMapper;
        this.actualEmployerMapper = actualEmployerMapper;
        this.planMapper = planMapper;
        this.policyMapper = policyMapper;
        this.policyMemberMapper = policyMemberMapper;
        this.commissionMapper = commissionMapper;
        this.pricingService = pricingService;
        this.policyMemberService = policyMemberService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    // effectiveAt/terminatedAt are plain strings, not LocalDateTime: both the
    // web date-picker (value-format="YYYY-MM-DD") and the miniprogram's
    // <picker mode="date"> send a bare "yyyy-MM-dd" with no time component,
    // which Jackson's default LocalDateTime deserializer rejects outright.
    // parseImportDate() below tolerantly parses date-only or full-datetime
    // strings, the same helper the bulk CSV/XLSX import already uses.
    public record PersonIn(int enterpriseId, String name, String phone, String idNumber, String occupation, String occupationClass,
                            Integer positionId, String effectiveAt, String terminatedAt) {}
    public record PersonUpdate(String name, String phone, String idNumber, Integer positionId, String effectiveAt, String terminatedAt) {}
    public record BulkPersonRow(String name, String idNumber, String phone) {}
    public record BulkPersonIn(int enterpriseId, int positionId, List<BulkPersonRow> rows) {}

    private InsuredPerson enrich(InsuredPerson x) {
        PolicyMember latestMember = policyMemberMapper.findLatestForPerson(x.getId());
        if (latestMember != null) {
            x.setEffectiveAt(latestMember.getEffectiveAt());
            x.setTerminatedAt(latestMember.getTerminatedAt());
        }
        x.setStatus(policyMemberService.effectivePersonStatus(x, latestMember != null ? latestMember.getTerminatedAt() : null));
        // x.getPolicyId() is cleared the moment a stop is scheduled (even a
        // future-dated 临时日结 auto-expiry) — once effectivePersonStatus has
        // decided the person is still actually active, fall back to the
        // still-open PolicyMember's policyId so 保险产品/保单号 don't go
        // blank while the row still says 在保.
        Integer policyId = x.getPolicyId();
        if (policyId == null && "active".equals(x.getStatus()) && latestMember != null) policyId = latestMember.getPolicyId();

        Enterprise enterprise = enterpriseMapper.findById(x.getEnterpriseId());
        WorkPosition position = x.getPositionId() != null ? positionMapper.findById(x.getPositionId()) : null;
        ActualEmployer employer = position != null && position.getActualEmployerId() != null ? actualEmployerMapper.findById(position.getActualEmployerId()) : null;
        InsurancePlan plan = position != null && position.getPlanId() != null ? planMapper.findById(position.getPlanId()) : null;
        Policy policy = policyId != null ? policyMapper.findById(policyId) : null;
        x.setEnterpriseName(enterprise != null ? enterprise.getName() : "");
        x.setPositionName(position != null ? position.getName() : x.getOccupation());
        x.setActualEmployerName(employer != null ? employer.getName() : (position != null ? position.getActualEmployer() : ""));
        x.setPlanId(plan != null ? plan.getId() : null);
        x.setPlanName(plan != null ? plan.getName() : "");
        x.setInsurer(plan != null ? plan.getInsurer() : "");
        x.setPolicyNo(policy != null ? policy.getPolicyNo() : "");
        x.setPolicyStatus(policy != null ? policy.getStatus() : "");
        x.setEffectiveMode(plan != null ? plan.getEffectiveMode() : "");
        x.setBillingMode(plan != null ? plan.getBillingMode() : "");
        if (plan != null) {
            AgentCommission relation = commissionMapper.findActiveRelation(x.getEnterpriseId(), plan.getId());
            x.setPricing(pricingService.snapshot(plan, relation, pricingService.planPriceForClass(plan, x.getOccupationClass())));
        }
        return x;
    }

    @GetMapping("/insured")
    public List<Object> list(@RequestParam(defaultValue = "") String q, User user) {
        Integer scoped = "enterprise".equals(user.getRole()) && user.getEnterpriseId() != null ? user.getEnterpriseId() : null;
        return personMapper.search(scoped, q).stream().map(x -> InternalPricingFilter.strip(enrich(x), user, objectMapper)).toList();
    }

    @PostMapping("/insured")
    public InsuredPerson create(@RequestBody PersonIn data, User user) {
        if (enterpriseMapper.findById(data.enterpriseId()) == null) throw ApiException.notFound("企业不存在");
        if ("enterprise".equals(user.getRole()) && !user.getEnterpriseId().equals(data.enterpriseId())) throw ApiException.forbidden("无权操作该单位");
        if (!IdNumberValidator.isValid(data.idNumber())) throw ApiException.badRequest("身份证号格式不正确");
        if (personMapper.countDuplicateIdNumber(data.enterpriseId(), data.idNumber(), -1) > 0) throw ApiException.conflict("该身份证号已在本单位参保，请勿重复添加");
        InsuredPerson item = new InsuredPerson();
        item.setEnterpriseId(data.enterpriseId());
        item.setName(data.name());
        item.setPhone(data.phone() == null ? "" : data.phone());
        item.setIdNumber(data.idNumber());
        item.setOccupation(data.occupation() == null ? "" : data.occupation());
        item.setOccupationClass(data.occupationClass() == null ? "3类" : data.occupationClass());
        item.setPositionId(data.positionId());
        item.setStatus("pending");
        if (data.positionId() != null) {
            WorkPosition position = positionMapper.findById(data.positionId());
            if (position == null || !position.getEnterpriseId().equals(data.enterpriseId()) || !"approved".equals(position.getStatus())) {
                throw ApiException.badRequest("只能选择本单位已审核通过的有效岗位");
            }
            item.setOccupation(position.getName());
            item.setOccupationClass(position.getOccupationClass());
        }
        item.setCreatedAt(LocalDateTime.now());
        personMapper.insert(item);
        LocalDateTime effectiveAt = requireParsedDate(data.effectiveAt(), "生效");
        LocalDateTime terminatedAt = requireParsedDate(data.terminatedAt(), "停保");
        if (effectiveAt != null || terminatedAt != null) {
            Integer policyId = policyMemberService.correctDates(item, effectiveAt, terminatedAt);
            if (policyId != null) {
                item.setPolicyId(policyId);
                item.setStatus(terminatedAt != null ? "stopped" : "active");
                personMapper.update(item);
            }
        }
        auditService.log(user, "create", "insured_person", String.valueOf(item.getId()));
        return enrich(item);
    }

    private LocalDateTime requireParsedDate(String raw, String label) {
        if (raw == null || raw.isBlank()) return null;
        LocalDateTime parsed = parseImportDate(raw);
        if (parsed == null) throw ApiException.badRequest(label + "时间格式不正确，应为 yyyy-MM-dd");
        return parsed;
    }

    @PatchMapping("/insured/{id}")
    public InsuredPerson update(@PathVariable int id, @RequestBody PersonUpdate data, User user) {
        InsuredPerson item = personMapper.findById(id);
        if (item == null) throw ApiException.notFound("参保员工不存在");
        if ("enterprise".equals(user.getRole()) && !user.getEnterpriseId().equals(item.getEnterpriseId())) throw ApiException.forbidden("无权操作该员工");
        if (data.idNumber() != null && !data.idNumber().equals(item.getIdNumber())) {
            if (!IdNumberValidator.isValid(data.idNumber())) throw ApiException.badRequest("身份证号格式不正确");
            if (personMapper.countDuplicateIdNumber(item.getEnterpriseId(), data.idNumber(), id) > 0) throw ApiException.conflict("该身份证号已在本单位参保，请勿重复添加");
        }
        if (data.positionId() != null) {
            WorkPosition position = positionMapper.findById(data.positionId());
            if (position == null || !position.getEnterpriseId().equals(item.getEnterpriseId()) || !"approved".equals(position.getStatus())) {
                throw ApiException.badRequest("只能选择本单位已审核通过的有效岗位");
            }
            item.setPositionId(position.getId());
            item.setOccupation(position.getName());
            item.setOccupationClass(position.getOccupationClass());
        }
        if (data.name() != null) item.setName(data.name());
        if (data.phone() != null) item.setPhone(data.phone());
        if (data.idNumber() != null) item.setIdNumber(data.idNumber());
        LocalDateTime effectiveAt = requireParsedDate(data.effectiveAt(), "生效");
        LocalDateTime terminatedAt = requireParsedDate(data.terminatedAt(), "停保");
        if (effectiveAt != null || terminatedAt != null) {
            Integer policyId = policyMemberService.correctDates(item, effectiveAt, terminatedAt);
            if (policyId != null) {
                item.setPolicyId(policyId);
                item.setStatus(terminatedAt != null ? "stopped" : "active");
            }
        }
        personMapper.update(item);
        auditService.log(user, "update", "insured_person", String.valueOf(id));
        return enrich(item);
    }

    @PatchMapping("/insured/{id}/status")
    public InsuredPerson setStatus(@PathVariable int id, @RequestParam("status") String status, User user) {
        InsuredPerson item = personMapper.findById(id);
        if (item == null) throw ApiException.notFound("参保员工不存在");
        if ("enterprise".equals(user.getRole()) && !user.getEnterpriseId().equals(item.getEnterpriseId())) throw ApiException.forbidden("无权操作该员工");
        String previous = item.getStatus();
        if ("active".equals(status) && !"active".equals(previous)) {
            item.setPolicyId(policyMemberService.activate(item));
        } else if ("active".equals(previous) && !"active".equals(status)) {
            policyMemberService.terminate(item);
            item.setPolicyId(null);
        }
        item.setStatus(status);
        personMapper.update(item);
        auditService.log(user, "status_change", "insured_person", String.valueOf(id), status);
        return enrich(item);
    }

    @GetMapping("/insured/{id}/policy-members")
    public List<PolicyMember> policyMembers(@PathVariable int id, User user) {
        InsuredPerson item = personMapper.findById(id);
        if (item == null) throw ApiException.notFound("参保员工不存在");
        if ("enterprise".equals(user.getRole()) && !user.getEnterpriseId().equals(item.getEnterpriseId())) throw ApiException.forbidden("无权查看该员工");
        List<PolicyMember> rows = policyMemberMapper.findByPerson(id);
        for (PolicyMember pm : rows) {
            Policy policy = policyMapper.findById(pm.getPolicyId());
            InsurancePlan plan = policy != null ? planMapper.findById(policy.getPlanId()) : null;
            pm.setPolicyNo(policy != null ? policy.getPolicyNo() : "");
            pm.setInsurer(plan != null ? plan.getInsurer() : "");
            pm.setPlanName(plan != null ? plan.getName() : "");
            pm.setEffectiveMode(plan != null ? plan.getEffectiveMode() : "");
        }
        return rows;
    }

    @GetMapping(value = "/insured/import-template", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> importTemplate() throws IOException {
        // 投保单位/实际工作单位/岗位名称 are optional: leave blank to use the
        // enterprise/position selected at upload time (backward compatible
        // with the miniprogram, which doesn't send these columns). Fill them
        // in to import multiple different units/positions in one file.
        // 生效日期/停保日期 are optional too: leave a row's column blank for the
        // old behaviour (enrollment rows land as 'pending' for manual review;
        // termination rows use "now" as the stop time).
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("批量导入模板");
            var header = sheet.createRow(0);
            String[] headers = {"姓名", "身份证号", "手机号", "投保单位", "实际工作单位", "岗位名称", "生效日期", "停保日期"};
            var headerStyle = workbook.createCellStyle();
            var headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            for (int index = 0; index < headers.length; index++) {
                header.createCell(index).setCellValue(headers[index]);
                header.getCell(index).setCellStyle(headerStyle);
                sheet.setColumnWidth(index, (index == 1 ? 23 : index >= 3 && index <= 5 ? 24 : 16) * 256);
            }
            var example = sheet.createRow(1);
            String[] values = {"张三", "340123199001011234", "13800000000", "", "", "", "2026-01-01", ""};
            for (int index = 0; index < values.length; index++) example.createCell(index).setCellValue(values[index]);
            workbook.write(output);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=insured-import-template.xlsx")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(output.toByteArray());
        }
    }

    private static final java.time.format.DateTimeFormatter[] IMPORT_DATE_FORMATS = {
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
    };

    private LocalDateTime parseImportDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.strip();
        try { return LocalDateTime.parse(s); } catch (Exception ignored) {}
        for (var fmt : IMPORT_DATE_FORMATS) {
            try {
                return s.length() <= 10 ? java.time.LocalDate.parse(s, fmt).atStartOfDay() : LocalDateTime.parse(s, fmt);
            } catch (Exception ignored) {}
        }
        return null;
    }

    public record BulkResult(boolean ok, int created, List<Map<String, Object>> errors, List<Integer> ids) {}

    @PostMapping("/insured/bulk")
    public BulkResult bulkAdd(@RequestBody BulkPersonIn data, User user) {
        if ("enterprise".equals(user.getRole()) && !user.getEnterpriseId().equals(data.enterpriseId())) throw ApiException.forbidden("无权操作该单位");
        WorkPosition position = positionMapper.findById(data.positionId());
        if (position == null || !position.getEnterpriseId().equals(data.enterpriseId()) || !"approved".equals(position.getStatus())) {
            throw ApiException.badRequest("只能选择本单位已审核通过的岗位");
        }
        List<Map<String, Object>> errors = new ArrayList<>();
        List<InsuredPerson> created = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int rowNo = 1;
        for (BulkPersonRow row : data.rows()) {
            rowNo++;
            String identity = row.idNumber() == null ? "" : row.idNumber().strip();
            String name = row.name() == null ? "" : row.name().strip();
            if (name.isBlank() || identity.isBlank()) { errors.add(Map.of("row", rowNo, "message", "姓名和身份证号必填")); continue; }
            if (seen.contains(identity) || personMapper.countByIdNumberGlobal(identity) > 0) { errors.add(Map.of("row", rowNo, "message", "身份证号重复")); continue; }
            seen.add(identity);
            InsuredPerson item = new InsuredPerson();
            item.setEnterpriseId(data.enterpriseId());
            item.setPositionId(position.getId());
            item.setName(name);
            item.setIdNumber(identity);
            item.setPhone(row.phone() == null ? "" : row.phone().strip());
            item.setOccupation(position.getName());
            item.setOccupationClass(position.getOccupationClass());
            item.setStatus("pending");
            item.setCreatedAt(LocalDateTime.now());
            created.add(item);
        }
        if (!errors.isEmpty()) return new BulkResult(false, 0, errors, List.of());
        for (InsuredPerson item : created) personMapper.insert(item);
        auditService.log(user, "bulk_create", "insured_person", created.stream().map(p -> String.valueOf(p.getId())).reduce((a, b) -> a + "," + b).orElse(""), "count=" + created.size());
        return new BulkResult(true, created.size(), List.of(), created.stream().map(InsuredPerson::getId).toList());
    }

    public record ImportResult(boolean ok, String kind, int success, List<Map<String, Object>> errors) {}

    @PostMapping("/insured/import-file")
    public ImportResult importFile(@RequestParam String kind, @RequestParam("enterprise_id") int enterpriseId,
                                    @RequestParam(name = "position_id", defaultValue = "0") int positionId,
                                    @RequestParam MultipartFile file, User user) throws IOException {
        if ("enterprise".equals(user.getRole()) && !user.getEnterpriseId().equals(enterpriseId)) throw ApiException.forbidden("无权操作该单位");
        if (enterpriseMapper.findById(enterpriseId) == null) throw ApiException.notFound("投保单位不存在");
        WorkPosition defaultPosition = null;
        if ("enrollment".equals(kind) && positionId != 0) {
            defaultPosition = positionMapper.findById(positionId);
            if (defaultPosition == null || !defaultPosition.getEnterpriseId().equals(enterpriseId) || !"approved".equals(defaultPosition.getStatus())) {
                throw ApiException.badRequest("批量参保必须选择本单位已审核通过的岗位");
            }
        }
        List<String[]> raw = new ArrayList<>();
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        try {
            if (name.endsWith(".xlsx")) {
                var workbook = WorkbookFactory.create(new ByteArrayInputStream(file.getBytes()));
                var sheet = workbook.getSheetAt(0);
                for (Row row : sheet) {
                    List<String> cells = new ArrayList<>();
                    row.forEach(cell -> cells.add(cell.toString().trim()));
                    raw.add(cells.toArray(new String[0]));
                }
            } else if (name.endsWith(".csv")) {
                String text = new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
                if (text.startsWith("﻿")) text = text.substring(1);
                for (String line : text.split("\\r?\\n")) {
                    if (line.isBlank()) continue;
                    raw.add(Arrays.stream(line.split(",")).map(String::trim).toArray(String[]::new));
                }
            } else {
                throw ApiException.badRequest("仅支持 CSV 或 XLSX 电子表格");
            }
        } catch (ApiException e) { throw e; } catch (Exception e) { throw ApiException.badRequest("电子表格解析失败：" + e.getMessage()); }
        if (raw.size() < 2) throw ApiException.badRequest("电子表格没有可导入的数据");

        Map<String, Integer> headers = new HashMap<>();
        String[] headerRow = raw.get(0);
        for (int i = 0; i < headerRow.length; i++) headers.put(headerRow[i].replace(" ", ""), i);
        Integer nameCol = headers.get("姓名");
        Integer idCol = headers.get("身份证号");
        Integer phoneCol = headers.get("手机号");
        // 以下三列可选：留空则沿用上传时选择的默认投保单位/岗位（兼容旧模板和小程序端），
        // 填写则按名称匹配，用于单次导入多个不同单位/岗位的名单（反馈条目 5）。
        Integer enterpriseCol = headers.get("投保单位");
        Integer employerCol = headers.get("实际工作单位");
        Integer positionCol = headers.get("岗位名称");
        Integer effectiveCol = headers.get("生效日期");
        Integer terminatedCol = headers.get("停保日期");
        if (idCol == null || ("enrollment".equals(kind) && nameCol == null)) throw ApiException.badRequest("模板必须包含姓名、身份证号；停保模板至少包含身份证号");

        List<Map<String, Object>> errors = new ArrayList<>();
        record PendingRow(String action, int enterpriseId, WorkPosition position, InsuredPerson existing, String name, String identity, String phone, LocalDateTime effectiveAt, LocalDateTime terminatedAt) {}
        List<PendingRow> pending = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<String, com.xbb.baojing.enterprise.Enterprise> enterpriseCache = new HashMap<>();
        for (int i = 1; i < raw.size(); i++) {
            int rowNo = i + 1;
            String[] row = raw.get(i);
            String identity = idCol < row.length ? row[idCol].strip() : "";
            String personName = nameCol != null && nameCol < row.length ? row[nameCol].strip() : "";
            String phone = phoneCol != null && phoneCol < row.length ? row[phoneCol].strip() : "";
            String rowEnterpriseName = enterpriseCol != null && enterpriseCol < row.length ? row[enterpriseCol].strip() : "";
            String rowEmployerName = employerCol != null && employerCol < row.length ? row[employerCol].strip() : "";
            String rowPositionName = positionCol != null && positionCol < row.length ? row[positionCol].strip() : "";
            String effectiveRaw = effectiveCol != null && effectiveCol < row.length ? row[effectiveCol].strip() : "";
            String terminatedRaw = terminatedCol != null && terminatedCol < row.length ? row[terminatedCol].strip() : "";
            if (identity.isBlank()) { errors.add(Map.of("row", rowNo, "message", "身份证号必填")); continue; }
            if (!IdNumberValidator.isValid(identity)) { errors.add(Map.of("row", rowNo, "message", "身份证号格式不正确")); continue; }
            if (seen.contains(identity)) { errors.add(Map.of("row", rowNo, "message", "表格内身份证号重复")); continue; }
            if (!effectiveRaw.isBlank() && parseImportDate(effectiveRaw) == null) { errors.add(Map.of("row", rowNo, "message", "生效日期格式不正确，应为 yyyy-MM-dd")); continue; }
            if (!terminatedRaw.isBlank() && parseImportDate(terminatedRaw) == null) { errors.add(Map.of("row", rowNo, "message", "停保日期格式不正确，应为 yyyy-MM-dd")); continue; }
            seen.add(identity);

            int rowEnterpriseId;
            if (rowEnterpriseName.isBlank()) {
                rowEnterpriseId = enterpriseId;
            } else {
                com.xbb.baojing.enterprise.Enterprise found = enterpriseCache.computeIfAbsent(rowEnterpriseName, enterpriseMapper::findByName);
                if (found == null) { errors.add(Map.of("row", rowNo, "message", "投保单位\"" + rowEnterpriseName + "\"不存在")); continue; }
                if ("enterprise".equals(user.getRole()) && !found.getId().equals(user.getEnterpriseId())) { errors.add(Map.of("row", rowNo, "message", "无权为其他投保单位导入数据")); continue; }
                rowEnterpriseId = found.getId();
            }

            InsuredPerson existing = personMapper.findByEnterpriseAndIdNumber(rowEnterpriseId, identity);
            if ("enrollment".equals(kind)) {
                if (personName.isBlank()) { errors.add(Map.of("row", rowNo, "message", "姓名必填")); continue; }
                if (existing != null && !"stopped".equals(existing.getStatus())) { errors.add(Map.of("row", rowNo, "message", "该员工已在保或待审核")); continue; }
                WorkPosition rowPosition = (rowEnterpriseId == enterpriseId && rowEmployerName.isBlank() && rowPositionName.isBlank()) ? defaultPosition : null;
                if (rowPosition == null) {
                    ActualEmployer employer = rowEmployerName.isBlank() ? null : actualEmployerMapper.findByEnterpriseAndName(rowEnterpriseId, rowEmployerName);
                    if (!rowEmployerName.isBlank() && employer == null) { errors.add(Map.of("row", rowNo, "message", "实际工作单位\"" + rowEmployerName + "\"不存在")); continue; }
                    rowPosition = rowPositionName.isBlank() ? null : positionMapper.findApprovedByName(rowEnterpriseId, employer != null ? employer.getId() : null, rowPositionName);
                    if (rowPosition == null) { errors.add(Map.of("row", rowNo, "message", "未找到匹配的已审核岗位，请填写实际工作单位与岗位名称，或先在岗位管理中创建并完成审核")); continue; }
                }
                LocalDateTime effectiveAt = parseImportDate(effectiveRaw);
                LocalDateTime terminatedAt = parseImportDate(terminatedRaw);
                if (effectiveAt != null && terminatedAt != null && !terminatedAt.isAfter(effectiveAt)) { errors.add(Map.of("row", rowNo, "message", "停保日期必须晚于生效日期")); continue; }
                pending.add(new PendingRow("create", rowEnterpriseId, rowPosition, existing, personName, identity, phone, effectiveAt, terminatedAt));
            } else {
                if (existing == null) { errors.add(Map.of("row", rowNo, "message", "未找到该单位参保员工")); continue; }
                if ("stopped".equals(existing.getStatus())) { errors.add(Map.of("row", rowNo, "message", "该员工已停保")); continue; }
                pending.add(new PendingRow("stop", rowEnterpriseId, null, existing, personName, identity, phone, null, parseImportDate(terminatedRaw)));
            }
        }
        if (!errors.isEmpty()) return new ImportResult(false, kind, 0, errors);

        int affected = 0;
        for (PendingRow row : pending) {
            if ("create".equals(row.action())) {
                InsuredPerson item = row.existing() != null ? row.existing() : new InsuredPerson();
                item.setName(row.name());
                item.setPhone(row.phone());
                item.setEnterpriseId(row.enterpriseId());
                item.setPositionId(row.position().getId());
                item.setIdNumber(row.identity());
                item.setOccupation(row.position().getName());
                item.setOccupationClass(row.position().getOccupationClass());
                // 生效日期 present -> the row is already-enrolled data being backfilled,
                // so activate immediately instead of leaving it 'pending' for review.
                item.setStatus(row.effectiveAt() != null ? (row.terminatedAt() != null ? "stopped" : "active") : "pending");
                if (row.existing() != null) personMapper.update(item);
                else { item.setCreatedAt(LocalDateTime.now()); personMapper.insert(item); }
                if (row.effectiveAt() != null) {
                    Integer policyId = policyMemberService.correctDates(item, row.effectiveAt(), row.terminatedAt());
                    if (policyId != null) { item.setPolicyId(policyId); personMapper.update(item); }
                }
            } else {
                InsuredPerson item = row.existing();
                if ("active".equals(item.getStatus())) { policyMemberService.terminate(item, row.terminatedAt()); item.setPolicyId(null); }
                item.setStatus("stopped");
                personMapper.update(item);
            }
            affected++;
        }
        auditService.log(user, "enrollment".equals(kind) ? "bulk_enrollment" : "bulk_termination", "insured_person", "", "count=" + affected + ";file=" + file.getOriginalFilename());
        return new ImportResult(true, kind, affected, List.of());
    }
}
