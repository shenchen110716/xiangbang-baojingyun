package com.xbb.baojing.claim;

import com.xbb.baojing.common.ApiException;
import com.xbb.baojing.common.User;
import com.xbb.baojing.enterprise.ActualEmployerMapper;
import com.xbb.baojing.enterprise.Enterprise;
import com.xbb.baojing.enterprise.EnterpriseMapper;
import com.xbb.baojing.enterprise.EmployerScopeAccess;
import com.xbb.baojing.insured.InsuredPerson;
import com.xbb.baojing.insured.InsuredPersonMapper;
import com.xbb.baojing.insured.Policy;
import com.xbb.baojing.insured.PolicyMapper;
import com.xbb.baojing.plan.InsurancePlan;
import com.xbb.baojing.plan.InsurancePlanMapper;
import com.xbb.baojing.position.WorkPosition;
import com.xbb.baojing.position.WorkPositionMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Ports backend/services/claims.py — the claim state machine, the required
 * document checklist, and claim_payload()'s heavy join+compute logic. */
@Service
public class ClaimService {
    public static final List<String[]> REQUIRED_DOCS = List.of(
            new String[]{"id_card", "被保险人身份证明"},
            new String[]{"labor_relation", "劳动关系证明"},
            new String[]{"diagnosis", "医疗诊断证明"},
            new String[]{"medical_record", "病历或出院记录"},
            new String[]{"invoice", "医疗发票和费用清单"},
            new String[]{"accident_proof", "事故经过及证明"},
            new String[]{"bank_card", "收款银行卡信息"}
    );
    public static final Set<String> REQUIRED_TYPES = REQUIRED_DOCS.stream().map(d -> d[0]).collect(java.util.stream.Collectors.toSet());

    public static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "reported", Set.of("collecting"),
            "collecting", Set.of("submitted"),
            "submitted", Set.of("insurer_review", "supplement"),
            "insurer_review", Set.of("supplement", "approved", "rejected"),
            "supplement", Set.of("submitted", "insurer_review"),
            "approved", Set.of("paid"),
            "paid", Set.of("closed"),
            "rejected", Set.of("closed"),
            "closed", Set.of()
    );

    private final EnterpriseMapper enterpriseMapper;
    private final InsuredPersonMapper personMapper;
    private final WorkPositionMapper positionMapper;
    private final ActualEmployerMapper actualEmployerMapper;
    private final PolicyMapper policyMapper;
    private final InsurancePlanMapper planMapper;
    private final ClaimDocumentMapper documentMapper;
    private final ClaimTimelineMapper timelineMapper;
    private final EmployerScopeAccess scopeAccess;

    public ClaimService(EnterpriseMapper enterpriseMapper, InsuredPersonMapper personMapper, WorkPositionMapper positionMapper,
                         ActualEmployerMapper actualEmployerMapper, PolicyMapper policyMapper, InsurancePlanMapper planMapper,
                         ClaimDocumentMapper documentMapper, ClaimTimelineMapper timelineMapper, EmployerScopeAccess scopeAccess) {
        this.enterpriseMapper = enterpriseMapper;
        this.personMapper = personMapper;
        this.positionMapper = positionMapper;
        this.actualEmployerMapper = actualEmployerMapper;
        this.policyMapper = policyMapper;
        this.planMapper = planMapper;
        this.documentMapper = documentMapper;
        this.timelineMapper = timelineMapper;
        this.scopeAccess = scopeAccess;
    }

    public void claimAccess(Claim item, User user) {
        if ("enterprise".equals(user.getRole()) && !user.getEnterpriseId().equals(item.getEnterpriseId())) {
            throw ApiException.forbidden("无权访问该理赔案件");
        }
        if (!user.getRole().equals("admin") && !user.getRole().equals("enterprise")) {
            throw ApiException.forbidden("无权访问理赔案件");
        }
        requirePersonScope(user, personMapper.findById(item.getPersonId()));
    }

    public void requirePersonScope(User user, InsuredPerson person) {
        WorkPosition position = person != null && person.getPositionId() != null ? positionMapper.findById(person.getPositionId()) : null;
        scopeAccess.requireEmployerAccess(user, position == null ? null : position.getActualEmployerId());
    }

    public boolean canAccessClaim(Claim item, User user) {
        try { claimAccess(item, user); return true; }
        catch (ApiException ignored) { return false; }
    }

    /** Ports prepare_claim_upload(): access check + closed-case guard +
     * enterprise-role node guard + the implicit reported->collecting
     * auto-transition on first upload. Returns true if the claim's status
     * was changed (caller must persist it). */
    public boolean prepareUpload(Claim item, User user) {
        claimAccess(item, user);
        if ("closed".equals(item.getStatus())) throw ApiException.conflict("已结案案件不能继续上传材料");
        if ("enterprise".equals(user.getRole()) && !Set.of("reported", "collecting", "supplement").contains(item.getStatus())) {
            throw ApiException.conflict("当前节点不允许企业上传材料");
        }
        if ("reported".equals(item.getStatus())) {
            item.setStatus("collecting");
            item.setCurrentHandler("企业经办人");
            addTimeline(item.getId(), "collecting", "开始收集理赔材料", "", user.getName());
            return true;
        }
        return false;
    }

    public void addTimeline(int claimId, String node, String action, String note, String operator) {
        ClaimTimeline t = new ClaimTimeline();
        t.setClaimId(claimId);
        t.setNode(node);
        t.setAction(action);
        t.setNote(note == null ? "" : note);
        t.setOperator(operator);
        t.setCreatedAt(LocalDateTime.now());
        timelineMapper.insert(t);
    }

    public List<ChecklistItem> checklist(int claimId) {
        List<ClaimDocument> docs = documentMapper.findByClaim(claimId);
        Set<String> valid = new HashSet<>();
        Map<String, ClaimDocument> latest = new LinkedHashMap<>();
        for (ClaimDocument d : docs) {
            if (Set.of("uploaded", "accepted").contains(d.getStatus())) valid.add(d.getDocType());
            latest.putIfAbsent(d.getDocType(), d);
        }
        List<ChecklistItem> result = new ArrayList<>();
        for (String[] pair : REQUIRED_DOCS) {
            ChecklistItem item = new ChecklistItem();
            item.setDocType(pair[0]);
            item.setName(pair[1]);
            item.setRequired(true);
            item.setUploaded(valid.contains(pair[0]));
            ClaimDocument doc = latest.get(pair[0]);
            item.setStatus(doc != null ? doc.getStatus() : "missing");
            item.setReviewNote(doc != null ? doc.getReviewNote() : "");
            result.add(item);
        }
        return result;
    }

    /** Ports claim_payload(): joins enterprise/person/position/employer/policy/plan,
     * computes document completeness, deadline/SLA overdue flags, and risk level. */
    public Claim buildPayload(Claim item) {
        Enterprise enterprise = enterpriseMapper.findById(item.getEnterpriseId());
        InsuredPerson person = personMapper.findById(item.getPersonId());
        WorkPosition position = person != null && person.getPositionId() != null ? positionMapper.findById(person.getPositionId()) : null;
        var employer = position != null && position.getActualEmployerId() != null ? actualEmployerMapper.findById(position.getActualEmployerId()) : null;
        Policy policy = person != null && person.getPolicyId() != null ? policyMapper.findById(person.getPolicyId()) : null;
        InsurancePlan plan = policy != null ? planMapper.findById(policy.getPlanId()) : null;

        List<ClaimDocument> docs = documentMapper.findByClaim(item.getId());
        Set<String> validTypes = new HashSet<>();
        for (ClaimDocument d : docs) if (Set.of("uploaded", "accepted").contains(d.getStatus()) && REQUIRED_TYPES.contains(d.getDocType())) validTypes.add(d.getDocType());
        List<String> missing = new ArrayList<>(REQUIRED_TYPES);
        missing.removeAll(validTypes);
        Collections.sort(missing);

        Integer deadlineDays = null;
        if (item.getDeadline() != null && item.getDeadline().length() >= 10) {
            try {
                LocalDate d = LocalDate.parse(item.getDeadline().substring(0, 10));
                deadlineDays = (int) (d.toEpochDay() - LocalDate.now().toEpochDay());
            } catch (Exception ignored) {}
        }
        boolean slaOverdue = false;
        if (item.getSlaDeadline() != null && item.getSlaDeadline().length() >= 16) {
            try {
                LocalDateTime dt = LocalDateTime.parse(item.getSlaDeadline().substring(0, 16), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                slaOverdue = dt.isBefore(LocalDateTime.now());
            } catch (Exception ignored) {}
        }
        String calculatedRisk;
        if ((deadlineDays != null && deadlineDays < 0) || slaOverdue) calculatedRisk = "high";
        else if ("supplement".equals(item.getStatus()) || (deadlineDays != null && deadlineDays <= 5)) calculatedRisk = "attention";
        else calculatedRisk = item.getRiskLevel();

        item.setEnterpriseName(enterprise != null ? enterprise.getName() : "");
        item.setPersonName(person != null ? person.getName() : "");
        item.setIdNumber(person != null ? person.getIdNumber() : "");
        item.setPositionName(position != null ? position.getName() : "");
        item.setActualEmployerName(employer != null ? employer.getName() : (position != null ? position.getActualEmployer() : ""));
        item.setPolicyNo(policy != null ? policy.getPolicyNo() : "");
        item.setPlanName(plan != null ? plan.getName() : "");
        item.setInsurer(plan != null ? plan.getInsurer() : "");
        item.setDocumentCount(docs.size());
        item.setMissingCount(missing.size());
        item.setMissingTypes(missing);
        item.setCompletePercent((int) Math.round((REQUIRED_TYPES.size() - missing.size()) / (double) REQUIRED_TYPES.size() * 100));
        item.setDeadlineDays(deadlineDays);
        item.setDeadlineOverdue(deadlineDays != null && deadlineDays < 0);
        item.setSlaOverdue(slaOverdue);
        item.setCalculatedRisk(calculatedRisk);
        return item;
    }
}
