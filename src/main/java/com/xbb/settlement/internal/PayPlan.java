package com.xbb.settlement.internal;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 计薪方案。**版本不可变**:改方案是发一个新版本、把旧版本置为失效,
 * 而不是就地改字段。
 *
 * <p>老系统用单独的 planSnapshotId 存快照,那意味着同一份方案有两处副本,迟早对不上。
 * 版本不可变之后,工资单直接引用它当时用的那一版,"快照和方案不一致"这种状态不存在。
 *
 * <p>**同一岗位同一时刻只能有一个生效方案**(数据库部分唯一索引兜底)。
 * 少了它,算薪时不知道用哪个 —— 而两个方案各自都合法,查起来毫无线索。
 */
@Entity
@Table(name = "pay_plan", schema = "settlement")
public class PayPlan {

    public enum PayType { HOURLY, DAILY, MONTHLY, PIECE }

    public enum Status { ACTIVE, EXPIRED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private long jobId;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false, length = 60)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "pay_type", nullable = false, length = 16)
    private PayType payType;

    @Column(name = "basic_salary_cents", nullable = false)
    private long basicSalaryCents;

    /** **佣金分账的基数。** 老系统拿 floatSalary 算佣金,不是拿应发总额。 */
    @Column(name = "float_salary_cents", nullable = false)
    private long floatSalaryCents;

    @Column(name = "fixed_salary_cents", nullable = false)
    private long fixedSalaryCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.ACTIVE;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "created_by", nullable = false)
    private long createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected PayPlan() { }

    PayPlan(long jobId, int version, String name, PayType payType,
            long basicSalaryCents, long floatSalaryCents, long fixedSalaryCents,
            LocalDate effectiveFrom, long createdBy) {
        if (basicSalaryCents < 0 || floatSalaryCents < 0 || fixedSalaryCents < 0) {
            throw new IllegalArgumentException("工资金额不能为负");
        }
        this.jobId = jobId;
        this.version = version;
        this.name = name;
        this.payType = payType;
        this.basicSalaryCents = basicSalaryCents;
        this.floatSalaryCents = floatSalaryCents;
        this.fixedSalaryCents = fixedSalaryCents;
        this.effectiveFrom = effectiveFrom;
        this.createdBy = createdBy;
    }

    public Long getId() { return id; }
    public long getJobId() { return jobId; }
    public int getVersion() { return version; }
    public String getName() { return name; }
    public PayType getPayType() { return payType; }
    public long getBasicSalaryCents() { return basicSalaryCents; }
    public long getFloatSalaryCents() { return floatSalaryCents; }
    public long getFixedSalaryCents() { return fixedSalaryCents; }
    public Status getStatus() { return status; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public LocalDate getEffectiveTo() { return effectiveTo; }
    public long getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }

    /** 置为失效。**不删除** —— 已出的工资单还引用着它,删了就解释不了金额怎么来的。 */
    void expire(LocalDate on) {
        this.status = Status.EXPIRED;
        this.effectiveTo = on;
    }
}
