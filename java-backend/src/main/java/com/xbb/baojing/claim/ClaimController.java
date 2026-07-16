package com.xbb.baojing.claim;

import com.xbb.baojing.common.ApiException;
import com.xbb.baojing.common.AuditService;
import com.xbb.baojing.common.FileTokenService;
import com.xbb.baojing.common.Rbac;
import com.xbb.baojing.common.User;
import com.xbb.baojing.insured.InsuredPerson;
import com.xbb.baojing.insured.InsuredPersonMapper;
import com.xbb.baojing.insured.Policy;
import com.xbb.baojing.insured.PolicyMapper;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ClaimController {
    private final ClaimMapper claimMapper;
    private final ClaimDocumentMapper documentMapper;
    private final ClaimTimelineMapper timelineMapper;
    private final ClaimService claimService;
    private final InsuredPersonMapper personMapper;
    private final PolicyMapper policyMapper;
    private final AuditService auditService;
    private final FileTokenService fileTokenService;
    private final String uploadsDir;
    private static final SecureRandom RANDOM = new SecureRandom();

    public ClaimController(ClaimMapper claimMapper, ClaimDocumentMapper documentMapper, ClaimTimelineMapper timelineMapper,
                            ClaimService claimService, InsuredPersonMapper personMapper, PolicyMapper policyMapper,
                            AuditService auditService, FileTokenService fileTokenService,
                            com.xbb.baojing.common.AppProperties props) {
        this.claimMapper = claimMapper;
        this.documentMapper = documentMapper;
        this.timelineMapper = timelineMapper;
        this.claimService = claimService;
        this.personMapper = personMapper;
        this.policyMapper = policyMapper;
        this.auditService = auditService;
        this.fileTokenService = fileTokenService;
        this.uploadsDir = props.getUploadsDir();
    }

    public record ClaimIn(Integer enterpriseId, Integer personId, String description, double amount, double medicalCost,
                           String accidentAt, String accidentPlace, String accidentType, String hospital, String diagnosis,
                           String contactName, String contactPhone) {}

    public record ClaimUpdate(String description, String hospital, String diagnosis, Double medicalCost, Double amount,
                               String contactName, String contactPhone, String insurerReportNo, String currentHandler,
                               String deadline, String slaDeadline, String rejectionReason, String reviewNote, String riskLevel) {}

    public record ClaimStatusIn(String status, String note, Double approvedAmount, String insurerReportNo,
                                 String rejectionReason, String paidAt, String currentHandler, String slaDeadline) {}

    public record ClaimDocumentIn(String name, String url, String docType) {}

    public record ClaimDocumentReviewIn(String status, String reviewNote) {}

    private ClaimDocument documentDto(ClaimDocument d) {
        FileTokenService.Token token = fileTokenService.makeToken("claim-document:" + d.getId());
        d.setUrl("/api/claims/" + d.getClaimId() + "/documents/" + d.getId() + "/download?token=" + token.token() + "&expires=" + token.expires());
        return d;
    }

    @GetMapping("/claims")
    public List<Claim> list(@RequestParam(defaultValue = "") String q, @RequestParam(name = "status", required = false) String status,
                             @RequestParam(required = false) String risk, @RequestParam(name = "enterprise_id", required = false) Integer enterpriseId,
                             User user) {
        Integer scoped = "enterprise".equals(user.getRole()) ? user.getEnterpriseId() : enterpriseId;
        List<Claim> rows = claimMapper.search(scoped, status).stream().filter(item -> claimService.canAccessClaim(item, user)).map(claimService::buildPayload).collect(java.util.stream.Collectors.toList());
        if (!q.isBlank()) {
            String needle = q.toLowerCase();
            rows = rows.stream().filter(c -> (c.getClaimNo() + c.getPersonName() + c.getEnterpriseName() + c.getActualEmployerName()).toLowerCase().contains(needle)).toList();
        }
        if (risk != null && !risk.isBlank()) rows = rows.stream().filter(c -> risk.equals(c.getCalculatedRisk())).toList();
        return rows;
    }

    @GetMapping("/claims/{id}")
    public Claim detail(@PathVariable int id, User user) {
        Claim item = claimMapper.findById(id);
        if (item == null) throw ApiException.notFound("理赔案件不存在");
        claimService.claimAccess(item, user);
        return claimService.buildPayload(item);
    }

    @PostMapping("/claims")
    public Claim create(@RequestBody ClaimIn data, User user) {
        if ("enterprise".equals(user.getRole()) && !user.getEnterpriseId().equals(data.enterpriseId())) throw ApiException.forbidden("无权提交该单位理赔");
        InsuredPerson person = personMapper.findById(data.personId());
        if (person == null || !person.getEnterpriseId().equals(data.enterpriseId())) throw ApiException.badRequest("被保险人不属于该投保单位");
        claimService.requirePersonScope(user, person);
        if (!"active".equals(person.getStatus())) throw ApiException.conflict("只能为当前在保员工提交工伤报案");
        if (person.getPolicyId() != null) {
            Policy policy = policyMapper.findById(person.getPolicyId());
            if (policy == null || !"active".equals(policy.getStatus())) throw ApiException.conflict("被保险人当前保单无效，请先核对保单");
        }
        String deadline;
        try {
            LocalDate accident = LocalDate.parse(data.accidentAt().substring(0, 10));
            deadline = accident.plusDays(30).toString();
        } catch (Exception e) { deadline = ""; }
        String slaDeadline = LocalDateTime.now().plusDays(2).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

        Claim item = new Claim();
        item.setEnterpriseId(data.enterpriseId());
        item.setPersonId(data.personId());
        item.setDescription(data.description() == null ? "" : data.description());
        item.setAmount(data.amount());
        item.setMedicalCost(data.medicalCost());
        item.setAccidentAt(data.accidentAt());
        item.setAccidentPlace(data.accidentPlace() == null ? "" : data.accidentPlace());
        item.setAccidentType(data.accidentType() == null || data.accidentType().isBlank() ? "工伤事故" : data.accidentType());
        item.setHospital(data.hospital() == null ? "" : data.hospital());
        item.setDiagnosis(data.diagnosis() == null ? "" : data.diagnosis());
        item.setContactName(data.contactName() == null ? "" : data.contactName());
        item.setContactPhone(data.contactPhone() == null ? "" : data.contactPhone());
        item.setDeadline(deadline);
        item.setSlaDeadline(slaDeadline);
        item.setCurrentHandler("企业经办人");
        item.setClaimNo("CLM-" + LocalDate.now().toString().replace("-", "") + "-" + randomHex(3));
        item.setCreatedAt(LocalDateTime.now());
        claimMapper.insert(item);
        claimService.addTimeline(item.getId(), "reported", "提交工伤报案", item.getDescription(), user.getName());
        auditService.log(user, "create", "claim", String.valueOf(item.getId()));
        return claimService.buildPayload(item);
    }

    private String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }

    @PatchMapping("/claims/{id}")
    public Claim update(@PathVariable int id, @RequestBody ClaimUpdate data, User user) {
        Claim item = claimMapper.findById(id);
        if (item == null) throw ApiException.notFound("理赔案件不存在");
        claimService.claimAccess(item, user);
        if ("enterprise".equals(user.getRole())) {
            if (!Set.of("reported", "collecting", "supplement").contains(item.getStatus())) throw ApiException.conflict("当前节点不允许企业修改报案信息");
            boolean touchesRestricted = data.insurerReportNo() != null || data.currentHandler() != null || data.deadline() != null ||
                    data.slaDeadline() != null || data.rejectionReason() != null || data.reviewNote() != null || data.riskLevel() != null;
            if (touchesRestricted) throw ApiException.forbidden("保司报案号、SLA、风险和审核意见仅平台可修改");
        }
        if (data.description() != null) item.setDescription(data.description());
        if (data.hospital() != null) item.setHospital(data.hospital());
        if (data.diagnosis() != null) item.setDiagnosis(data.diagnosis());
        if (data.medicalCost() != null) item.setMedicalCost(data.medicalCost());
        if (data.amount() != null) item.setAmount(data.amount());
        if (data.contactName() != null) item.setContactName(data.contactName());
        if (data.contactPhone() != null) item.setContactPhone(data.contactPhone());
        if (data.insurerReportNo() != null) item.setInsurerReportNo(data.insurerReportNo());
        if (data.currentHandler() != null) item.setCurrentHandler(data.currentHandler());
        if (data.deadline() != null) item.setDeadline(data.deadline());
        if (data.slaDeadline() != null) item.setSlaDeadline(data.slaDeadline());
        if (data.rejectionReason() != null) item.setRejectionReason(data.rejectionReason());
        if (data.reviewNote() != null) item.setReviewNote(data.reviewNote());
        if (data.riskLevel() != null) item.setRiskLevel(data.riskLevel());
        claimMapper.update(item);
        auditService.log(user, "update", "claim", String.valueOf(item.getId()));
        return item;
    }

    @PatchMapping("/claims/{id}/status")
    public Claim setStatus(@PathVariable int id, @RequestBody ClaimStatusIn data, User user) {
        Claim item = claimMapper.findById(id);
        if (item == null) throw ApiException.notFound("理赔案件不存在");
        claimService.claimAccess(item, user);
        if (!ClaimService.TRANSITIONS.getOrDefault(item.getStatus(), Set.of()).contains(data.status())) {
            throw ApiException.conflict("案件不能从 " + item.getStatus() + " 变更为 " + data.status());
        }
        if ("enterprise".equals(user.getRole()) && !"submitted".equals(data.status())) throw ApiException.forbidden("该节点需由平台理赔人员处理");
        if ("submitted".equals(data.status())) {
            List<String> uploaded = documentMapper.findUploadedTypes(item.getId());
            Set<String> missing = new HashSet<>(ClaimService.REQUIRED_TYPES);
            missing.removeAll(uploaded);
            if (!missing.isEmpty()) throw ApiException.conflict("材料未齐全，还缺少 " + missing.size() + " 项");
        }
        if ("insurer_review".equals(data.status()) && (data.insurerReportNo() == null || data.insurerReportNo().isBlank()) && item.getInsurerReportNo().isBlank()) {
            throw ApiException.conflict("请先登记保司报案号");
        }
        if ("approved".equals(data.status()) && data.approvedAmount() == null) throw ApiException.conflict("核赔通过时必须登记核赔金额");
        if ("rejected".equals(data.status()) && (data.rejectionReason() == null || data.rejectionReason().isBlank()) && (data.note() == null || data.note().isBlank())) {
            throw ApiException.conflict("拒赔时必须填写拒赔原因");
        }
        item.setStatus(data.status());
        if (data.approvedAmount() != null) item.setApprovedAmount(data.approvedAmount());
        if (data.insurerReportNo() != null) item.setInsurerReportNo(data.insurerReportNo());
        if (data.rejectionReason() != null) item.setRejectionReason(data.rejectionReason());
        if (data.slaDeadline() != null) item.setSlaDeadline(data.slaDeadline());
        if (data.note() != null && "admin".equals(user.getRole())) item.setReviewNote(data.note());
        Map<String, String> defaultHandlers = Map.of("submitted", "平台理赔专员", "insurer_review", "保险公司理赔岗", "supplement", "企业经办人",
                "approved", "平台财务", "paid", "平台理赔专员", "rejected", "平台理赔专员", "closed", "已归档");
        item.setCurrentHandler(data.currentHandler() != null ? data.currentHandler() : defaultHandlers.getOrDefault(data.status(), item.getCurrentHandler()));
        if ("paid".equals(data.status())) item.setPaidAt(data.paidAt() != null ? data.paidAt() : LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        claimMapper.update(item);
        claimService.addTimeline(item.getId(), data.status(), "理赔状态变更", data.note(), user.getName());
        auditService.log(user, "status_change", "claim", String.valueOf(item.getId()), data.status());
        return item;
    }

    @GetMapping("/claims/{id}/documents")
    public List<ClaimDocument> documents(@PathVariable int id, User user) {
        Claim item = claimMapper.findById(id);
        if (item == null) throw ApiException.notFound("理赔案件不存在");
        if ("enterprise".equals(user.getRole()) && !user.getEnterpriseId().equals(item.getEnterpriseId())) throw ApiException.forbidden("无权查看该案件");
        return documentMapper.findByClaim(id).stream().map(this::documentDto).toList();
    }

    @PostMapping("/claims/{id}/documents")
    public ClaimDocument addDocument(@PathVariable int id, @RequestBody ClaimDocumentIn data, User user) {
        Claim item = claimMapper.findById(id);
        if (item == null) throw ApiException.notFound("理赔案件不存在");
        boolean changed = claimService.prepareUpload(item, user);
        if (changed) claimMapper.update(item);
        ClaimDocument doc = new ClaimDocument();
        doc.setClaimId(id);
        doc.setName(data.name());
        doc.setUrl(data.url() == null ? "" : data.url());
        doc.setDocType(data.docType() == null || data.docType().isBlank() ? "other" : data.docType());
        doc.setCreatedAt(LocalDateTime.now());
        documentMapper.insert(doc);
        claimService.addTimeline(id, item.getStatus(), "上传材料：" + doc.getName(), doc.getDocType(), user.getName());
        auditService.log(user, "upload", "claim_document", String.valueOf(doc.getId()));
        return documentDto(doc);
    }

    @PostMapping("/claims/{id}/documents/upload")
    public ClaimDocument uploadDocument(@PathVariable int id, @RequestParam(defaultValue = "other") String docType,
                                         @RequestParam MultipartFile file, User user) throws IOException {
        Claim item = claimMapper.findById(id);
        if (item == null) throw ApiException.notFound("理赔案件不存在");
        boolean changed = claimService.prepareUpload(item, user);
        if (changed) claimMapper.update(item);

        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String suffix = original.contains(".") ? original.substring(original.lastIndexOf('.')).toLowerCase() : "";
        if (!Set.of(".jpg", ".jpeg", ".png", ".heic", ".pdf", ".doc", ".docx", ".xls", ".xlsx").contains(suffix)) {
            throw ApiException.badRequest("仅支持图片、PDF、Word、Excel材料");
        }
        if (file.getSize() > 20L * 1024 * 1024) throw ApiException.badRequest("单个材料不能超过20MB");

        Path folder = Paths.get(uploadsDir, "claims", String.valueOf(id));
        Files.createDirectories(folder);
        String stored = randomHex(8) + suffix;
        Files.write(folder.resolve(stored), file.getBytes());
        String url = "/uploads/claims/" + id + "/" + stored;

        ClaimDocument doc = new ClaimDocument();
        doc.setClaimId(id);
        doc.setName(original.isBlank() ? stored : original);
        doc.setUrl(url);
        doc.setDocType(docType);
        doc.setCreatedAt(LocalDateTime.now());
        documentMapper.insert(doc);
        claimService.addTimeline(id, item.getStatus(), "上传材料：" + doc.getName(), docType, user.getName());
        auditService.log(user, "upload", "claim_document", String.valueOf(doc.getId()));
        return documentDto(doc);
    }

    @GetMapping("/claims/{claimId}/documents/{documentId}/download")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> download(
            @PathVariable int claimId, @PathVariable int documentId, @RequestParam String token, @RequestParam long expires) throws IOException {
        if (!fileTokenService.verify("claim-document:" + documentId, expires, token)) throw ApiException.forbidden("下载链接无效或已过期");
        ClaimDocument document = documentMapper.findById(documentId);
        if (document == null || !document.getClaimId().equals(claimId)) throw ApiException.notFound("理赔材料不存在");
        if (document.getUrl().startsWith("http://") || document.getUrl().startsWith("https://")) {
            return org.springframework.http.ResponseEntity.status(302).header("Location", document.getUrl()).build();
        }
        Path path = Paths.get(".", document.getUrl());
        if (!Files.isRegularFile(path)) throw ApiException.notFound("文件不存在");
        org.springframework.core.io.Resource resource = new org.springframework.core.io.UrlResource(path.toUri());
        return org.springframework.http.ResponseEntity.ok().body(resource);
    }

    @PatchMapping("/claims/{claimId}/documents/{documentId}")
    public ClaimDocument reviewDocument(@PathVariable int claimId, @PathVariable int documentId, @RequestBody ClaimDocumentReviewIn data, User user) {
        Rbac.requireRole(user, "仅平台理赔人员可审核材料", "admin");
        Claim item = claimMapper.findById(claimId);
        ClaimDocument document = documentMapper.findById(documentId);
        if (item == null || document == null || !document.getClaimId().equals(claimId)) throw ApiException.notFound("理赔材料不存在");
        document.setStatus(data.status());
        document.setReviewNote(data.reviewNote() == null ? "" : data.reviewNote());
        documentMapper.update(document);
        if ("rejected".equals(data.status()) && !Set.of("paid", "rejected", "closed").contains(item.getStatus())) {
            item.setStatus("supplement");
            item.setCurrentHandler("企业经办人");
            claimMapper.update(item);
            claimService.addTimeline(item.getId(), "supplement", "材料驳回：" + document.getName(), data.reviewNote(), user.getName());
        } else {
            claimService.addTimeline(item.getId(), item.getStatus(), "材料审核：" + document.getName(), data.reviewNote() != null ? data.reviewNote() : data.status(), user.getName());
        }
        auditService.log(user, "review", "claim_document", String.valueOf(document.getId()), data.status());
        return documentDto(document);
    }

    @DeleteMapping("/claims/{claimId}/documents/{documentId}")
    public Map<String, Boolean> deleteDocument(@PathVariable int claimId, @PathVariable int documentId, User user) {
        Claim item = claimMapper.findById(claimId);
        ClaimDocument document = documentMapper.findById(documentId);
        if (item == null || document == null || !document.getClaimId().equals(claimId)) throw ApiException.notFound("理赔材料不存在");
        claimService.claimAccess(item, user);
        if ("enterprise".equals(user.getRole()) && !Set.of("reported", "collecting", "supplement").contains(item.getStatus())) throw ApiException.conflict("当前节点不允许删除材料");
        if ("closed".equals(item.getStatus())) throw ApiException.conflict("已结案材料不能删除");
        claimService.addTimeline(item.getId(), item.getStatus(), "删除材料：" + document.getName(), document.getDocType(), user.getName());
        documentMapper.delete(documentId);
        auditService.log(user, "delete", "claim_document", String.valueOf(documentId));
        return Map.of("ok", true);
    }

    @GetMapping("/claims/{id}/timeline")
    public List<ClaimTimeline> timeline(@PathVariable int id, User user) {
        Claim item = claimMapper.findById(id);
        if (item == null) throw ApiException.notFound("理赔案件不存在");
        if ("enterprise".equals(user.getRole()) && !user.getEnterpriseId().equals(item.getEnterpriseId())) throw ApiException.forbidden("无权查看该案件");
        return timelineMapper.findByClaim(id);
    }

    @GetMapping("/claims/{id}/checklist")
    public List<ChecklistItem> checklist(@PathVariable int id, User user) {
        Claim item = claimMapper.findById(id);
        if (item == null) throw ApiException.notFound("理赔案件不存在");
        if ("enterprise".equals(user.getRole()) && !user.getEnterpriseId().equals(item.getEnterpriseId())) throw ApiException.forbidden("无权查看该案件");
        return claimService.checklist(id);
    }
}
