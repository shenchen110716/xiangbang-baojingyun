package com.xbb.fund.internal;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "payout", schema = "fund")
public class Payout {

    public enum Status { PENDING, PAID, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "settlement_id", nullable = false, unique = true)
    private long settlementId;

    /**
     * 出资单位。<b>此前 payout 压根不知道钱该从谁的账户出</b> ——
     * 全平台一个账户时不成问题,按单位分账之后,不知道归属就是不知道扣谁的钱,
     * 而扣错了是把 A 公司的钱发给了 B 公司的工人。
     */
    @Column(name = "org_id")
    private Long orgId;


    @Column(name = "payee_user_id", nullable = false)
    private long payeeUserId;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Version
    private long version;

    protected Payout() { }

    public Payout(long settlementId, long payeeUserId, long amountCents) {
        this(settlementId, payeeUserId, amountCents, null);
    }

    /**
     * @param orgId 钱从哪个单位的账户出。<b>可能为 null</b> —— 老的代发单没有这个信息,
     *              岗位副本还没到时也没有。null 时从平台账户出(和按单位分账之前一致)。
     */
    public Payout(long settlementId, long payeeUserId, long amountCents, Long orgId) {
        this.orgId = orgId;
        this.settlementId = settlementId;
        this.payeeUserId = payeeUserId;
        this.amountCents = amountCents;
    }

    public void disburse() {
        if (status == Status.CANCELLED) throw new IllegalStateException("该结算已作废,不能发放");
        if (status != Status.PENDING) throw new IllegalStateException("只有待发放状态可以发放");
        this.status = Status.PAID;
        this.paidAt = Instant.now();
    }

    /**
     * 作废待发放记录。
     *
     * <p>**已发放的必须报错,不能静默返回。** 钱出去了再作废结算,原来这里
     * 什么都不做:不冲正、不告警、佣金不动、报表仍记收入——账面上"这单不该付钱",
     * 而三千块已经离开监管账户,没有任何人会知道。这种情况需要人工冲正,
     * 不该被一个 return 吞掉。
     */
    public void cancel() {
        if (status == Status.PAID) {
            throw new IllegalStateException(
                    "该笔已发放,不能直接作废,需要走冲正流程。payoutId=" + id);
        }
        if (status == Status.CANCELLED) {
            return;   // 重复作废幂等
        }
        this.status = Status.CANCELLED;
    }

    public Long getId() { return id; }
    public long getSettlementId() { return settlementId; }
    public long getPayeeUserId() { return payeeUserId; }
    /** @return null 表示从平台账户出 */
    public Long getOrgId() { return orgId; }
    public long getAmountCents() { return amountCents; }
    public Status getStatus() { return status; }
    public Instant getPaidAt() { return paidAt; }
}
