package com.xbb.settlement.internal;

import com.xbb.settlement.api.SettlementApi;
import com.xbb.settlement.api.SettlementVoided;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbb.identity.api.IdentityApi;
import com.xbb.identity.api.Role;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
class SettlementService implements SettlementApi {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SettlementService.class);

    private final SettlementRepository settlements;
    private final SettlementOutboxRepository outbox;
    private final ObjectMapper json;
    private final IdentityApi identityApi;
    private final PayPlanRepository payPlans;
    private final PayPlanFactorRepository payPlanFactors;
    private final SettlementPostedJobRepository postedJobs;
    private final SettlementApprovedOrgRepository approvedOrgs;
    private final com.xbb.attendance.api.AttendanceApi attendanceApi;

    SettlementService(SettlementRepository settlements,
                     SettlementOutboxRepository outbox, ObjectMapper json,
                       IdentityApi identityApi,
                      PayPlanRepository payPlans, PayPlanFactorRepository payPlanFactors,
                      SettlementPostedJobRepository postedJobs,
                      SettlementApprovedOrgRepository approvedOrgs,
                      com.xbb.attendance.api.AttendanceApi attendanceApi) {
        this.attendanceApi = attendanceApi;
        this.payPlans = payPlans;
        this.payPlanFactors = payPlanFactors;
        this.postedJobs = postedJobs;
        this.approvedOrgs = approvedOrgs;
        this.settlements = settlements;
        this.outbox = outbox;
        this.json = json;
        this.identityApi = identityApi;
    }

    /**
     * 平台运维操作,要求 {@link Role#PLATFORM_OPS}。
     *
     * <p>这不是归属校验的替代品,而是它缺席时唯一说得通的东西:这几个动作的
     * "主人"是平台自己,不是某个用户。角色每次向身份域现查,不读 JWT 声明,
     * 这样收回权限立刻生效(理由同 OutboxOpsController)。
     */
    private void requirePlatformOps(long callerUserId) {
        if (!identityApi.hasRole(callerUserId, Role.PLATFORM_OPS)) {
            throw new AccessDeniedException("需要平台运维权限");
        }
    }

    private String serialize(Object event) {
        try {
            return json.writeValueAsString(event);
        } catch (Exception e) {
            // 序列化不了就别让这步业务成功——事件发不出去,下游永远补不回来
            throw new IllegalStateException("事件无法序列化: " + event, e);
        }
    }

    @Override
    @Transactional("settlementTransactionManager")
    public void voidSettlement(long settlementId, String reason, long callerUserId) {
        requirePlatformOps(callerUserId);
        Settlement settlement = settlements.findById(settlementId)
                .orElseThrow(() -> new IllegalArgumentException("结算记录不存在"));
        settlement.voidSettlement(reason);
        settlements.save(settlement);
        SettlementVoided voided = new SettlementVoided(settlementId, reason, Instant.now());
        outbox.save(new SettlementOutboxEvent(java.util.UUID.randomUUID().toString(),
                SettlementVoided.class.getName(), serialize(voided)));
    }

    @Override
    @Transactional(transactionManager = "settlementTransactionManager", readOnly = true)
    public Optional<SettlementView> findById(long settlementId, long callerUserId) {
        return settlements.findById(settlementId)
                .filter(s -> maySee(s, callerUserId))
                .map(this::toView);
    }

    /**
     * 谁看得到这张工资单:工人本人,或岗位所属组织的法人代表。
     *
     * <p><b>看不到时返回空,而不是抛"无权访问"。</b>抛异常会顺带确认这张单存在 ——
     * 拿编号从 1 数上去,虽然读不到金额,却能数出平台一共发了多少笔工资。
     */
    private boolean maySee(Settlement s, long callerUserId) {
        if (s.getWorkerUserId() == callerUserId) {
            return true;
        }
        return postedJobs.findById(s.getJobId())
                .flatMap(job -> approvedOrgs.findById(job.getOrgId()))
                .map(org -> org.getLegalRepUserId() == callerUserId)
                .orElse(false);
    }

    @Override
    @Transactional(transactionManager = "settlementTransactionManager", readOnly = true)
    public Optional<PayslipView> payslip(long settlementId, long callerUserId) {
        return settlements.findById(settlementId)
                .filter(s -> maySee(s, callerUserId))
                .map(this::toPayslip);
    }

    private PayslipView toPayslip(Settlement s) {
        PayPlan plan = s.getPayPlanId() == null ? null
                : payPlans.findById(s.getPayPlanId()).orElse(null);

        List<PayslipLine> lines = parseLines(s);
        if (lines.isEmpty()) {
            // 方案启用前的老工资单没有明细。给一行兜底而不是留空 ——
            // 空白让人以为"系统坏了",一行"岗位工价"至少说清了这笔是怎么来的
            lines = List.of(new PayslipLine("岗位工价（未启用计薪方案）", s.getAmountCents()));
        }

        // 天数用明细算不出来,只有考勤知道。没有方案的老单子按 0 天呈现
        int workDays = s.getPayPlanId() == null ? 0 : workDaysOf(s);

        return new PayslipView(s.getId(), s.getApplicationId(), s.getJobId(), s.getWorkerUserId(),
                s.getAmountCents(), s.getStatus().name(), s.getVoidReason(),
                s.getPayPlanId(), plan == null ? null : plan.getName(),
                plan == null ? null : plan.getPayType().name(),
                s.getMinutes(), workDays, lines);
    }

    /**
     * 明细存的是计薪那一刻的快照。**解析失败不抛异常** ——
     * 工资条打不开比金额少一行明细严重得多,而这两件事里只有前者会让人打电话来。
     */
    private List<PayslipLine> parseLines(Settlement s) {
        if (s.getBreakdown() == null || s.getBreakdown().isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(s.getBreakdown(),
                    new com.fasterxml.jackson.core.type.TypeReference<List<PayslipLine>>() { });
        } catch (Exception e) {
            log.error("工资单 {} 的明细解析失败,本次只显示总额。原文: {}",
                    s.getId(), s.getBreakdown(), e);
            return List.of();
        }
    }

    private int workDaysOf(Settlement s) {
        try {
            return attendanceApi.confirmedSummary(s.getApplicationId()).workDays();
        } catch (RuntimeException e) {
            // 出勤天数只是展示信息,拿不到不该让整张工资条打不开
            log.warn("工资单 {} 取出勤天数失败,按 0 展示", s.getId(), e);
            return 0;
        }
    }

    @Override
    @Transactional(transactionManager = "settlementTransactionManager", readOnly = true)
    public Optional<SettlementView> findByApplicationId(long applicationId) {
        return settlements.findByApplicationId(applicationId).map(this::toView);
    }

    private SettlementView toView(Settlement s) {
        return new SettlementView(
                s.getId(), s.getApplicationId(), s.getJobId(), s.getWorkerUserId(),
                s.getAmountCents(), com.xbb.settlement.api.SettlementStatus.valueOf(s.getStatus().name()), s.getVoidReason());
    }

    @Override
    @Transactional(transactionManager = "settlementTransactionManager", readOnly = true)
    public List<SettlementView> listMySettlements(long workerUserId) {
        return settlements.findByWorkerUserIdOrderByIdDesc(workerUserId).stream()
                .map(s -> new SettlementView(s.getId(), s.getApplicationId(), s.getJobId(),
                        s.getWorkerUserId(), s.getAmountCents(), com.xbb.settlement.api.SettlementStatus.valueOf(s.getStatus().name()), s.getVoidReason()))
                .toList();
    }

    // ─────────────── 计薪方案 ───────────────

    @Override
    @Transactional("settlementTransactionManager")
    public long publishPayPlan(long jobId, String name, String payType,
                               long basicSalaryCents, long floatSalaryCents, long fixedSalaryCents,
                               LocalDate effectiveFrom, List<FactorSpec> factors, long callerUserId) {
        requireJobOwner(jobId, callerUserId);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("方案名称不能为空");
        }
        if (effectiveFrom == null) {
            throw new IllegalArgumentException("必须指定生效日期");
        }
        PayPlan.PayType type = parsePayType(payType);
        if (basicSalaryCents == 0 && floatSalaryCents == 0 && fixedSalaryCents == 0) {
            // 全零的方案算出来永远是 0 工资。让它建成功等于埋一个"发不出钱"的雷
            throw new IllegalArgumentException("基本、浮动、固定至少要有一项大于零");
        }

        // 先把原来生效的那版置为失效。**不删除** —— 已出的工资单还引用着它,
        // 删了就解释不了金额怎么来的。
        payPlans.findByJobIdAndStatus(jobId, PayPlan.Status.ACTIVE).ifPresent(old -> {
            old.expire(effectiveFrom);
            payPlans.save(old);
        });
        payPlans.flush();   // 部分唯一索引在提交时才校验,先刷出去让顺序确定

        int nextVersion = payPlans.findByJobIdOrderByVersionDesc(jobId).stream()
                .mapToInt(PayPlan::getVersion).max().orElse(0) + 1;

        PayPlan plan = payPlans.save(new PayPlan(jobId, nextVersion, name.trim(), type,
                basicSalaryCents, floatSalaryCents, fixedSalaryCents, effectiveFrom, callerUserId));

        for (FactorSpec f : factors == null ? List.<FactorSpec>of() : factors) {
            payPlanFactors.save(new PayPlanFactor(plan.getId(),
                    parseFactorType(f.factorType()), f.name(), f.amountCents()));
        }
        log.warn("计薪方案发布: job={} v{} 基本={} 浮动={} 固定={} 操作人={}",
                jobId, nextVersion, basicSalaryCents, floatSalaryCents, fixedSalaryCents, callerUserId);
        return plan.getId();
    }

    @Override
    @Transactional(transactionManager = "settlementTransactionManager", readOnly = true)
    public List<PayPlanView> listPayPlans(long jobId, long callerUserId) {
        // 无关的人拿到空结果,不是异常(铁律 5.1) —— 抛"只有法人可以…"
        // 等于确认了这个岗位存在
        if (!isJobOwner(jobId, callerUserId)) {
            return List.of();
        }
        return payPlans.findByJobIdOrderByVersionDesc(jobId).stream().map(this::toView).toList();
    }

    @Override
    @Transactional(transactionManager = "settlementTransactionManager", readOnly = true)
    public Optional<PayPlanView> activePayPlan(long jobId, long callerUserId) {
        // 无关的人拿到空结果,不是异常(铁律 5.1) —— 抛"只有法人可以…"
        // 等于确认了这个岗位存在
        if (!isJobOwner(jobId, callerUserId)) {
            return Optional.empty();
        }
        return payPlans.findByJobIdAndStatus(jobId, PayPlan.Status.ACTIVE).map(this::toView);
    }

    private PayPlanView toView(PayPlan p) {
        List<FactorSpec> factors = payPlanFactors.findByPlanId(p.getId()).stream()
                .map(f -> new FactorSpec(f.getFactorType().name(), f.getName(), f.getAmountCents()))
                .toList();
        return new PayPlanView(p.getId(), p.getJobId(), p.getVersion(), p.getName(),
                p.getPayType().name(), p.getBasicSalaryCents(), p.getFloatSalaryCents(),
                p.getFixedSalaryCents(), p.getStatus().name(),
                p.getEffectiveFrom(), p.getEffectiveTo(), factors);
    }

    /** 归属校验:只有岗位所属组织的法人代表能设方案。 */
    /** 只判断,不抛。**读接口用它** —— 读不到该回空而不是报错(铁律 5.1)。 */
    @Override
    @Transactional(transactionManager = "settlementTransactionManager", readOnly = true)
    public boolean mayViewPayPlans(long jobId, long callerUserId) {
        return isJobOwner(jobId, callerUserId);
    }

    private boolean isJobOwner(long jobId, long callerUserId) {
        try {
            requireJobOwner(jobId, callerUserId);
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private void requireJobOwner(long jobId, long callerUserId) {
        SettlementPostedJob job = postedJobs.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("岗位不存在或副本尚未落地: " + jobId));
        SettlementApprovedOrg org = approvedOrgs.findById(job.getOrgId())
                .orElseThrow(() -> new IllegalStateException("组织未通过审核"));
        if (org.getLegalRepUserId() != callerUserId) {
            throw new IllegalStateException("只有组织法人代表可以设置计薪方案");
        }
    }

    private static PayPlan.PayType parsePayType(String s) {
        try {
            return PayPlan.PayType.valueOf(s == null ? "" : s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的计薪方式: " + s);
        }
    }

    private static PayPlanFactor.FactorType parseFactorType(String s) {
        try {
            return PayPlanFactor.FactorType.valueOf(s == null ? "" : s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的调整项类型: " + s);
        }
    }
}
