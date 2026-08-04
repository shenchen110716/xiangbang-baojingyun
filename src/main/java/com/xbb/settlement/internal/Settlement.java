package com.xbb.settlement.internal;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "settlement", schema = "settlement")
public class Settlement {

    // 不含 PAID——发钱是否成功是资金域的事(结算⊥资金,见 Plan6),本域只有
    // "算出来了(PENDING)"和"作废(VOIDED)"两种状态,没有权威地知道钱是否已经发出去。
    public enum Status { PENDING, VOIDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false, unique = true)
    private long applicationId;

    @Column(name = "job_id", nullable = false)
    private long jobId;

    @Column(name = "worker_user_id", nullable = false)
    private long workerUserId;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "void_reason")
    private String voidReason;

    /** 按哪个方案版本算的。null = 没有生效方案,退回按岗位工价一口价(旧行为)。 */
    @Column(name = "pay_plan_id")
    private Long payPlanId;

    /** 算这笔工资时用的已确认工时(分钟)。存下来是为了事后能解释金额怎么来的。 */
    @Column(nullable = false)
    private int minutes;

    /**
     * 明细快照(JSON):基本/浮动/固定/各调整项各多少。
     *
     * <p>**存下来而不是每次重算**:方案可以发新版本,但已出的工资单必须永远解释得通。
     * 老系统的工资单只有一个总额,出账疑问时只能靠人回忆。
     */
    @Column(columnDefinition = "text")
    private String breakdown;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Version
    private long version;

    protected Settlement() { }

    /** 带方案与工时的构造。金额由 WageCalculator 算好后传进来。 */
    Settlement(long applicationId, long jobId, long workerUserId, long amountCents,
               Long payPlanId, int minutes, String breakdown) {
        this(applicationId, jobId, workerUserId, amountCents);
        this.payPlanId = payPlanId;
        this.minutes = minutes;
        this.breakdown = breakdown;
    }

    public Settlement(long applicationId, long jobId, long workerUserId, long amountCents) {
        this.applicationId = applicationId;
        this.jobId = jobId;
        this.workerUserId = workerUserId;
        this.amountCents = amountCents;
    }

    public void voidSettlement(String reason) {
        if (status != Status.PENDING) throw new IllegalStateException("只有待结算状态可以作废");
        this.status = Status.VOIDED;
        this.voidReason = reason;
    }

    public Long getId() { return id; }
    public long getApplicationId() { return applicationId; }
    public long getJobId() { return jobId; }
    public long getWorkerUserId() { return workerUserId; }
    public long getAmountCents() { return amountCents; }
    public Status getStatus() { return status; }
    public String getVoidReason() { return voidReason; }

    public Long getPayPlanId() { return payPlanId; }
    public int getMinutes() { return minutes; }
    public String getBreakdown() { return breakdown; }
}
