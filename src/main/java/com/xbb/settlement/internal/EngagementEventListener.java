package com.xbb.settlement.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbb.engagement.api.EngagementCompleted;
import com.xbb.settlement.api.SettlementCalculated;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;


/**
 * 结算的触发点是**履约完成**,不是录用(主文档 §9.3 枢纽事件)。
 * Plan7 当时因为履约域没有完成态,临时挂在 ApplicationAccepted 上(录用即视为可结算),
 * 那是记录在案的已知缺口;Plan9 补上完成态后迁到这里。
 */
@Component
class EngagementEventListener {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(EngagementEventListener.class);

    private final SettlementRepository settlements;
    private final SettlementOutboxRepository outbox;
    private final PayPlanRepository payPlans;
    private final PayPlanFactorRepository payPlanFactors;
    private final com.xbb.attendance.api.AttendanceApi attendanceApi;
    private final ObjectMapper json;

    EngagementEventListener(SettlementRepository settlements, SettlementOutboxRepository outbox,
                             PayPlanRepository payPlans, PayPlanFactorRepository payPlanFactors,
                             com.xbb.attendance.api.AttendanceApi attendanceApi,
                             ObjectMapper json) {
        this.settlements = settlements;
        this.outbox = outbox;
        this.payPlans = payPlans;
        this.payPlanFactors = payPlanFactors;
        this.attendanceApi = attendanceApi;
        this.json = json;
    }

    // 同步(非 @Async)AFTER_COMMIT,理由见 org.internal.IdentityEventListener 的注释(审计修复)。
    /**
     * `@EventListener` 而非 AFTER_COMMIT:该事件由履约域的 outbox 中继投递。
     * AFTER_COMMIT 的监听器要等中继事务提交后才跑,那时 outbox 行已是 PUBLISHED,
     * 这里再抛异常事件就永久丢了(理由详见 AbstractOutboxRelay)。
     */
    @EventListener
    @Transactional(transactionManager = "settlementTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(EngagementCompleted event) {
        // 幂等键用**业务键**(履约单)而不是 eventId:定义"这单算过工资了"的是履约单,
        // 不是某一次投递的编号。按 eventId 去重只挡得住同一次投递的重试,
        // 换个 eventId 的重投会撞上 settlement.application_id 的唯一约束——
        // 而撞约束会让中继永远重试、事件卡死(表上那条约束是最后一道防线,不是幂等手段)。
        if (settlements.findByApplicationId(event.applicationId()).isPresent()) return;

        Computed computed = computeAmount(event);
        Settlement settlement = settlements.save(new Settlement(
                event.applicationId(), event.jobId(), event.workerUserId(),
                computed.amountCents(), computed.payPlanId(), computed.minutes(), computed.breakdown()));

        // 关键:结算记录与 outbox 行**在同一个事务里**落库。
        // 要么都成功要么都回滚,不会出现"结算生成了但下游永远收不到通知"。
        SettlementCalculated calculated = new SettlementCalculated(
                settlement.getId(), event.applicationId(), event.workerUserId(),
                computed.amountCents(), Instant.now());
        outbox.save(new SettlementOutboxEvent(
                event.eventId(), SettlementCalculated.class.getName(), serialize(calculated)));
    }

    private String serialize(SettlementCalculated event) {
        try {
            return json.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("结算事件序列化失败", e);
        }
    }

    private record Computed(long amountCents, Long payPlanId, int minutes, String breakdown) { }

    /**
     * 按生效的计薪方案与已确认工时算钱。
     *
     * <p><b>没有生效方案时退回原行为</b>(按岗位工价一口价)。这不是偷懒:
     * 计薪方案是后加的、按岗位逐个启用的,已经在跑的履约链路不该因为这次改动断掉。
     * 退回时 payPlanId 记 null,事后一眼能看出这笔是按老口径算的。
     */
    private Computed computeAmount(EngagementCompleted event) {
        PayPlan plan = payPlans.findByJobIdAndStatus(event.jobId(), PayPlan.Status.ACTIVE).orElse(null);
        if (plan == null) {
            return new Computed(event.wageCents(), null, 0, null);
        }
        var summary = attendanceApi.confirmedSummary(event.applicationId());
        var factors = payPlanFactors.findByPlanId(plan.getId()).stream()
                .map(f -> new WageCalculator.Factor(
                        WageCalculator.FactorType.valueOf(f.getFactorType().name()),
                        f.getName(), f.getAmountCents()))
                .toList();

        WageCalculator.Result r;
        try {
            r = WageCalculator.compute(new WageCalculator.Plan(
                            WageCalculator.PayType.valueOf(plan.getPayType().name()),
                            plan.getBasicSalaryCents(), plan.getFloatSalaryCents(),
                            plan.getFixedSalaryCents(), factors),
                    summary.minutes(), summary.workDays());
        } catch (RuntimeException e) {
            // 算不出来时**不静默退回一口价** —— 那会让"方案配错了"变成一笔看起来正常的工资。
            // 抛出去让事件留在 outbox 里重试,运营改完方案就能补上。
            log.error("按方案计薪失败: application={} job={} plan={} 原因={}",
                    event.applicationId(), event.jobId(), plan.getId(), e.getMessage());
            throw e;
        }

        String breakdown;
        try {
            breakdown = json.writeValueAsString(r.lines());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("工资明细序列化失败", e);
        }
        log.info("按方案计薪: application={} plan=v{} 工时={}分 出勤={}天 应发={}分",
                event.applicationId(), plan.getVersion(), summary.minutes(), summary.workDays(),
                r.grossCents());
        return new Computed(r.grossCents(), plan.getId(), summary.minutes(), breakdown);
    }
}
