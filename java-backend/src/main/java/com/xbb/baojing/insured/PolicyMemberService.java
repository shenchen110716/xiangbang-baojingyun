package com.xbb.baojing.insured;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbb.baojing.agent.AgentCommission;
import com.xbb.baojing.agent.AgentCommissionMapper;
import com.xbb.baojing.common.ApiException;
import com.xbb.baojing.plan.InsurancePlan;
import com.xbb.baojing.plan.InsurancePlanMapper;
import com.xbb.baojing.plan.PricingService;
import com.xbb.baojing.plan.PricingSnapshot;
import com.xbb.baojing.position.WorkPosition;
import com.xbb.baojing.position.WorkPositionMapper;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** Ports backend/services/policy_members.py — the "PolicyMember bridge" that
 * lazily creates Policy/PolicyMember rows the first time a person's status
 * flips into 'active', and closes (never deletes/reuses) the open one when
 * they leave 'active'. Re-enrollment always makes a new row, preserving
 * coverage history instead of overwriting it. */
@Service
public class PolicyMemberService {
    private final PolicyMapper policyMapper;
    private final PolicyMemberMapper policyMemberMapper;
    private final WorkPositionMapper positionMapper;
    private final InsurancePlanMapper planMapper;
    private final AgentCommissionMapper commissionMapper;
    private final PricingService pricingService;
    private final ObjectMapper objectMapper;
    private static final SecureRandom RANDOM = new SecureRandom();

    public PolicyMemberService(PolicyMapper policyMapper, PolicyMemberMapper policyMemberMapper,
                                WorkPositionMapper positionMapper, InsurancePlanMapper planMapper,
                                AgentCommissionMapper commissionMapper, PricingService pricingService,
                                ObjectMapper objectMapper) {
        this.policyMapper = policyMapper;
        this.policyMemberMapper = policyMemberMapper;
        this.positionMapper = positionMapper;
        this.planMapper = planMapper;
        this.commissionMapper = commissionMapper;
        this.pricingService = pricingService;
        this.objectMapper = objectMapper;
    }

    private String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }

    private Policy findOrCreatePolicy(int enterpriseId, int planId) {
        Policy existing = policyMapper.findByEnterpriseAndPlan(enterpriseId, planId);
        if (existing != null) return existing;
        Policy policy = new Policy();
        policy.setPolicyNo("POL-" + java.time.LocalDate.now().toString().replace("-", "") + "-" + randomHex(3));
        policy.setEnterpriseId(enterpriseId);
        policy.setPlanId(planId);
        policy.setStatus("active");
        policy.setStartDate(java.time.LocalDate.now().toString());
        policy.setCreatedAt(LocalDateTime.now());
        policyMapper.insert(policy);
        return policy;
    }

    private InsurancePlan planFor(InsuredPerson person) {
        if (person.getPositionId() == null) return null;
        WorkPosition position = positionMapper.findById(person.getPositionId());
        return position != null && position.getPlanId() != null ? planMapper.findById(position.getPlanId()) : null;
    }

    /** 即时生效方案：生效时间就是参保（操作）时间本身；次日生效方案：最早
     * 为操作日次日零点。 */
    public LocalDateTime earliestEffectiveAt(InsurancePlan plan, LocalDateTime operationTime) {
        LocalDateTime operation = operationTime != null ? operationTime : LocalDateTime.now();
        return "immediate".equals(plan.getEffectiveMode())
                ? operation
                : LocalDateTime.of(operation.toLocalDate().plusDays(1), LocalTime.MIDNIGHT);
    }

    /** 即时生效方案：最早停保时间为生效时间往后推 24 小时（最短保障周期为
     * 整 24 小时）；次日生效方案（或方案未知）：最早为操作日次日零点（最短
     * 保障周期为一个完整自然日），与生效时间无关。 */
    public LocalDateTime earliestTerminationAt(InsurancePlan plan, LocalDateTime effectiveAt, LocalDateTime operationTime) {
        if (plan != null && "immediate".equals(plan.getEffectiveMode()) && effectiveAt != null) {
            return effectiveAt.plusHours(24);
        }
        LocalDateTime operation = operationTime != null ? operationTime : LocalDateTime.now();
        return LocalDateTime.of(operation.toLocalDate().plusDays(1), LocalTime.MIDNIGHT);
    }

    private InsurancePlan validateDates(InsuredPerson person, LocalDateTime effectiveAt,
                                        LocalDateTime terminatedAt, LocalDateTime operationTime) {
        InsurancePlan plan = planFor(person);
        if (plan == null) return null;
        LocalDateTime operation = operationTime != null ? operationTime : LocalDateTime.now();
        PolicyMember latest = policyMemberMapper.findLatestForPerson(person.getId());
        if (effectiveAt != null) {
            LocalDateTime earliest = earliestEffectiveAt(plan, operation);
            if (effectiveAt.isBefore(earliest)) {
                String rule = "immediate".equals(plan.getEffectiveMode()) ? "参保（操作）时间" : "操作日次日 00:00";
                throw ApiException.badRequest("生效时间不合理：该方案最早可于" + rule + "生效（" + earliest + "）");
            }
        }
        if (terminatedAt != null) {
            LocalDateTime candidateEffectiveForTerm = effectiveAt != null ? effectiveAt : (latest != null ? latest.getEffectiveAt() : null);
            LocalDateTime earliest = earliestTerminationAt(plan, candidateEffectiveForTerm, operation);
            if (terminatedAt.isBefore(earliest)) {
                String rule = "immediate".equals(plan.getEffectiveMode()) ? "生效时间往后 24 小时" : "操作日次日 00:00";
                throw ApiException.badRequest("停保时间不合理：最早可于" + rule + "停保（" + earliest + "）");
            }
        }
        LocalDateTime candidateEffective = effectiveAt != null ? effectiveAt : (latest != null ? latest.getEffectiveAt() : null);
        LocalDateTime candidateTermination = terminatedAt != null ? terminatedAt : (latest != null ? latest.getTerminatedAt() : null);
        if (candidateEffective != null && candidateTermination != null && !candidateTermination.isAfter(candidateEffective)) {
            throw ApiException.badRequest("停保时间必须晚于生效时间");
        }
        return plan;
    }

    /** Returns the resolved policyId to set on the person (or null to no-op),
     * matching activate_person_policy()'s early-return conditions exactly. */
    public Integer activate(InsuredPerson person) {
        return activate(person, null);
    }

    /** Same as activate(), but records the given moment as 生效时间/effective_at
     * instead of "now" — used for manual date correction (EmployeeEditorDialog)
     * and backdated bulk-import rows (EnrollmentController's 生效日期 column). */
    public Integer activate(InsuredPerson person, LocalDateTime effectiveAt) {
        if (person.getPositionId() == null) return null;
        WorkPosition position = positionMapper.findById(person.getPositionId());
        if (position == null || position.getPlanId() == null) return null;
        InsurancePlan plan = planMapper.findById(position.getPlanId());
        if (plan == null) return null;
        LocalDateTime operation = LocalDateTime.now();
        if (effectiveAt != null) validateDates(person, effectiveAt, null, operation);
        LocalDateTime targetEffectiveAt = effectiveAt != null ? effectiveAt : earliestEffectiveAt(plan, operation);
        PolicyMember latest = policyMemberMapper.findLatestForPerson(person.getId());
        if (latest != null && latest.getTerminatedAt() != null && targetEffectiveAt.isBefore(latest.getTerminatedAt())) {
            throw ApiException.badRequest("生效时间不能早于上一保障期间的停保时间（" + latest.getTerminatedAt() + "）");
        }

        Policy policy = findOrCreatePolicy(person.getEnterpriseId(), plan.getId());
        AgentCommission relation = commissionMapper.findActiveRelation(person.getEnterpriseId(), plan.getId());
        PricingSnapshot snapshot = pricingService.snapshot(plan, relation, pricingService.planPriceForClass(plan, person.getOccupationClass()));

        PolicyMember member = new PolicyMember();
        member.setPolicyId(policy.getId());
        member.setPersonId(person.getId());
        try {
            member.setRateSnapshotJson(objectMapper.writeValueAsString(snapshot));
        } catch (Exception e) {
            member.setRateSnapshotJson("{}");
        }
        member.setEffectiveAt(targetEffectiveAt);
        member.setStatus("active");
        member.setCreatedAt(LocalDateTime.now());
        policyMemberMapper.insert(member);
        return policy.getId();
    }

    /** Closes the person's open coverage period, if any. Caller is
     * responsible for clearing person.policyId afterwards. */
    public void terminate(InsuredPerson person) {
        terminate(person, null);
    }

    /** Same as terminate(), but records the given moment as 停保时间/terminated_at
     * instead of "now" — used for manual date correction and backdated bulk
     * termination-import rows (EnrollmentController's 停保日期 column). */
    public void terminate(InsuredPerson person, LocalDateTime terminatedAt) {
        PolicyMember open = policyMemberMapper.findOpenForPerson(person.getId());
        if (open != null) {
            LocalDateTime operation = LocalDateTime.now();
            InsurancePlan plan = planFor(person);
            LocalDateTime target;
            if (terminatedAt != null) {
                validateDates(person, null, terminatedAt, operation);
                target = terminatedAt;
            } else {
                // 即时生效方案默认停保时间为生效时间往后 24 小时；次日生效
                // 方案为操作日次日零点，如与生效时间冲突则顺延到生效当天的
                // 次日零点，保证最短保障周期为一个完整自然日。
                target = earliestTerminationAt(plan, open.getEffectiveAt(), operation);
                if (!target.isAfter(open.getEffectiveAt())) {
                    target = LocalDateTime.of(open.getEffectiveAt().toLocalDate().plusDays(1), LocalTime.MIDNIGHT);
                }
            }
            open.setTerminatedAt(target);
            open.setStatus("terminated");
            policyMemberMapper.update(open);
        }
    }

    /** Ports the "manually correct 生效时间/停保时间 on an existing coverage
     * period" flow requested for the workers list edit dialog — distinct from
     * activate()/terminate() (which are driven by the person's status
     * transitioning), this directly rewrites the latest PolicyMember row's
     * timestamps, or creates one via activate() if none exists yet and an
     * effectiveAt was given (e.g. backfilling a person who was never run
     * through the normal activation flow). Returns the resulting policyId,
     * or null if nothing changed. */
    public Integer correctDates(InsuredPerson person, LocalDateTime effectiveAt, LocalDateTime terminatedAt) {
        if (effectiveAt == null && terminatedAt == null) return person.getPolicyId();
        validateDates(person, effectiveAt, terminatedAt, LocalDateTime.now());
        PolicyMember latest = policyMemberMapper.findLatestForPerson(person.getId());
        if (latest == null) {
            if (effectiveAt == null && terminatedAt == null) return null; // nothing to backfill onto
            // terminatedAt alone (no explicit effectiveAt) on a brand-new
            // person is the "临时日结" one-shot flow: activate now with the
            // plan's default earliest effective time, then immediately apply
            // the given termination — not a no-op (feedback item 10).
            Integer policyId = activate(person, effectiveAt);
            if (policyId == null || terminatedAt == null) return policyId;
            latest = policyMemberMapper.findLatestForPerson(person.getId());
        }
        if (effectiveAt != null) latest.setEffectiveAt(effectiveAt);
        if (terminatedAt != null) latest.setTerminatedAt(terminatedAt);
        latest.setStatus(latest.getTerminatedAt() != null ? "terminated" : "active");
        policyMemberMapper.updateDates(latest);
        return latest.getPolicyId();
    }
}
