package com.xbb.baojing.insured;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbb.baojing.agent.AgentCommission;
import com.xbb.baojing.agent.AgentCommissionMapper;
import com.xbb.baojing.plan.InsurancePlan;
import com.xbb.baojing.plan.InsurancePlanMapper;
import com.xbb.baojing.plan.PricingService;
import com.xbb.baojing.plan.PricingSnapshot;
import com.xbb.baojing.position.WorkPosition;
import com.xbb.baojing.position.WorkPositionMapper;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;

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

    /** Returns the resolved policyId to set on the person (or null to no-op),
     * matching activate_person_policy()'s early-return conditions exactly. */
    public Integer activate(InsuredPerson person) {
        return activate(person, LocalDateTime.now());
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
        member.setEffectiveAt(effectiveAt != null ? effectiveAt : LocalDateTime.now());
        member.setStatus("active");
        member.setCreatedAt(LocalDateTime.now());
        policyMemberMapper.insert(member);
        return policy.getId();
    }

    /** Closes the person's open coverage period, if any. Caller is
     * responsible for clearing person.policyId afterwards. */
    public void terminate(InsuredPerson person) {
        terminate(person, LocalDateTime.now());
    }

    /** Same as terminate(), but records the given moment as 停保时间/terminated_at
     * instead of "now" — used for manual date correction and backdated bulk
     * termination-import rows (EnrollmentController's 停保日期 column). */
    public void terminate(InsuredPerson person, LocalDateTime terminatedAt) {
        PolicyMember open = policyMemberMapper.findOpenForPerson(person.getId());
        if (open != null) {
            open.setTerminatedAt(terminatedAt != null ? terminatedAt : LocalDateTime.now());
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
        PolicyMember latest = policyMemberMapper.findLatestForPerson(person.getId());
        if (latest == null) {
            if (effectiveAt == null) return null; // nothing to backfill onto
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
