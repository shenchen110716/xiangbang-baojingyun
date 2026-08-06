package com.xbb.fund.internal;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 一笔借支。平台先把钱垫给工人,之后从工资里逐笔扣回。
 *
 * <p>本金 {@code amountCents} **不可变**,变的是未还金额 {@code outstandingCents}。
 * 两个数分开存是为了事后说得清:"借了 2000、已还 1500、还欠 500" ——
 * 只存一个余额的话,还完就再也看不出当初借了多少。
 */
@Entity
@Table(name = "advance", schema = "fund")
public class Advance {

    public enum Status {
        /** 还有欠款。 */
        ACTIVE,
        /** 已还清。 */
        CLEARED,
        /** 撤销(批错了)。**只有一分钱都没还过的才能撤销**,否则还款记录会变成孤儿。 */
        CANCELLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 批这笔借支的用工单位。<b>NULL = 平台垫的</b>(老数据,以及平台自己批的)。
     *
     * <p>还款只能从这家的结算里扣 —— 不记的话,甲公司批的借支会从
     * 乙公司给同一个工人的付款里扣走,而两边都不会报错。
     */
    @Column(name = "org_id")
    private Long orgId;


    @Column(name = "worker_user_id", nullable = false)
    private long workerUserId;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Column(name = "outstanding_cents", nullable = false)
    private long outstandingCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    @Column(length = 200)
    private String reason;

    @Column(name = "granted_by", nullable = false)
    private long grantedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "cleared_at")
    private Instant clearedAt;

    /**
     * 乐观锁。同一个人的借支会被两条路径同时改:发工资时自动抵扣、运营登记线下还款。
     *
     * <p>老系统在这里用的是进程内的 {@code ConcurrentHashMap} 加锁 ——
     * **多实例部署时那个锁形同虚设**,两个实例各锁各的,照样同时扣。
     * 版本号由数据库裁决,和实例数无关。
     */
    @Version
    private long version;

    protected Advance() { }

    public Advance(long workerUserId, long amountCents, String reason, long grantedBy) {
        this(workerUserId, amountCents, reason, grantedBy, null);
    }

    /** @param orgId 批这笔的用工单位;null 表示平台垫的 */
    public Advance(long workerUserId, long amountCents, String reason, long grantedBy, Long orgId) {
        this.orgId = orgId;
        if (amountCents <= 0) {
            throw new IllegalArgumentException("借支金额必须为正数");
        }
        this.workerUserId = workerUserId;
        this.amountCents = amountCents;
        this.outstandingCents = amountCents;
        this.reason = reason;
        this.grantedBy = grantedBy;
    }

    /**
     * 还一笔。**返回实际还掉的金额** —— 传进来的可能比欠款多,
     * 那时只还欠款那么多,多出来的部分由调用方处理(工资里那部分照发给工人)。
     *
     * @return 本次实际抵扣的金额(分)
     */
    public long repay(long amountCents) {
        if (amountCents <= 0) {
            throw new IllegalArgumentException("还款金额必须为正数");
        }
        if (status != Status.ACTIVE) {
            throw new IllegalStateException("这笔借支已" + (status == Status.CLEARED ? "结清" : "撤销") + ",不能再还款");
        }
        long applied = Math.min(amountCents, outstandingCents);
        outstandingCents -= applied;
        if (outstandingCents == 0) {
            status = Status.CLEARED;
            clearedAt = Instant.now();
        }
        return applied;
    }

    /** 撤销。批错了才用,**还过款的不能撤** —— 那些钱已经从工资里扣走了。 */
    public void cancel() {
        if (status != Status.ACTIVE) {
            throw new IllegalStateException("只有生效中的借支可以撤销");
        }
        if (outstandingCents != amountCents) {
            throw new IllegalStateException("已经还过款的借支不能撤销,请改为登记还款直至结清");
        }
        status = Status.CANCELLED;
    }

    public Long getId() { return id; }
    /** @return null 表示平台垫的 */
    public Long getOrgId() { return orgId; }

    public long getWorkerUserId() { return workerUserId; }
    public long getAmountCents() { return amountCents; }
    public long getOutstandingCents() { return outstandingCents; }
    public Status getStatus() { return status; }
    public String getReason() { return reason; }
    public long getGrantedBy() { return grantedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getClearedAt() { return clearedAt; }
}
