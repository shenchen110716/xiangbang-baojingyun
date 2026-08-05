package com.xbb.fund.internal;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 一笔还款。**每笔都留痕** —— 老系统那条规则("每笔借支与还款留痕")
 * 在争议时是唯一说得清的东西:工人问"我这个月工资怎么少了 500",
 * 没有这张表就只能凭印象回答。
 */
@Entity
@Table(name = "advance_repayment", schema = "fund")
public class AdvanceRepayment {

    public enum Source {
        /** 发工资时自动抵扣。 */
        SALARY_DEDUCTION,
        /** 线下还款后人工登记。 */
        MANUAL
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "advance_id", nullable = false)
    private long advanceId;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Source source;

    /** 工资抵扣时对应的结算单;人工还款为空。**它也是幂等键的一半**(见 V8 迁移里的唯一索引)。 */
    @Column(name = "settlement_id")
    private Long settlementId;

    @Column(name = "recorded_by")
    private Long recordedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected AdvanceRepayment() { }

    public static AdvanceRepayment fromSalary(long advanceId, long amountCents, long settlementId) {
        AdvanceRepayment r = new AdvanceRepayment();
        r.advanceId = advanceId;
        r.amountCents = amountCents;
        r.source = Source.SALARY_DEDUCTION;
        r.settlementId = settlementId;
        return r;
    }

    public static AdvanceRepayment manual(long advanceId, long amountCents, long recordedBy) {
        AdvanceRepayment r = new AdvanceRepayment();
        r.advanceId = advanceId;
        r.amountCents = amountCents;
        r.source = Source.MANUAL;
        r.recordedBy = recordedBy;
        return r;
    }

    public Long getId() { return id; }
    public long getAdvanceId() { return advanceId; }
    public long getAmountCents() { return amountCents; }
    public Source getSource() { return source; }
    public Long getSettlementId() { return settlementId; }
    public Long getRecordedBy() { return recordedBy; }
    public Instant getCreatedAt() { return createdAt; }
}
