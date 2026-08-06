package com.xbb.job.internal;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "job", schema = "job")
public class Job {

    public enum Status { OPEN, CLOSED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 个人发单方。<b>和 org_id 恰好有一个非空</b>(数据库 CHECK 保证)。
     *
     * <p>不给个人造一个"个人组织" —— organization 上已经有一串针对企业的约束
     * (信用代码、法人、审核状态),硬塞进去要么放宽那些约束、要么填假数据。
     */
    @Column(name = "poster_user_id")
    private Long posterUserId;

    /** 总价模式:发单方只填一个总数,员工价和佣金按比例算出来。 */
    @Column(name = "total_price_cents")
    private Long totalPriceCents;

    /**
     * 国标行政区划代码。佣金比例按「类目 + 地区」配。
     * <b>必须是选出来的,不能从地址文本里解析</b> —— 解析错了不会报错,
     * 只会静默套上另一个地区的比例。
     */
    @Column(name = "region_code", length = 6)
    private String regionCode;


    /**
     * 用工单位。<b>个人发单时为 null</b> —— 和 posterUserId 恰好有一个非空。
     *
     * <p>类型从 {@code long} 改成 {@code Long}:原始类型没法表达"没有单位",
     * 用 0 当哨兵的话,哪天真有 id=0 的组织就分不清了。
     */
    @Column(name = "org_id")
    private Long orgId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String description;

    @Column(name = "wage_cents", nullable = false)
    private long wageCents;

    /**
     * 这个岗位的工作地点。**可空** —— 老岗位没有,不填的也没有。
     * 不在这里抄一份单位地址:抄了之后单位改地址,这些岗位还留着旧的。
     */
    @Column(name = "work_address", length = 200)
    private String workAddress;

    /** 名额(§4.2)。招满即关闭,匹配的硬约束"名额未满"靠它。 */
    @Column(nullable = false)
    private int headcount = 1;

    @Column(name = "filled_count", nullable = false)
    private int filledCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.OPEN;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    /** 名额扣减是并发点:没有它,两个人能同时读到"还剩 1 个"然后都录用成功。 */
    @Version
    private long version;

    protected Job() { }

    public Job(long orgId, String title, String description, long wageCents, int headcount) {
        this(orgId, title, description, wageCents, headcount, null);
    }

    public Job(long orgId, String title, String description, long wageCents, int headcount,
                String workAddress) {
        this.workAddress = workAddress;
        if (headcount < 1) {
            throw new IllegalArgumentException("名额至少为 1");
        }
        this.orgId = orgId;
        this.title = title;
        this.description = description;
        this.wageCents = wageCents;
        this.headcount = headcount;
    }

    /**
     * 占一个名额。占满自动关闭——"招满"和"关闭"是同一件事的两面,
     * 分开做迟早会出现"满了还在招"的状态。
     *
     * @return 本次是否因为占满而关闭(调用方据此决定要不要发关闭事件)
     * @throws IllegalStateException 岗位已关闭,或名额已满
     */
    public boolean fillOneSlot(Instant now) {
        if (status != Status.OPEN) {
            throw new IllegalStateException("岗位已关闭,不能再录用");
        }
        if (filledCount >= headcount) {
            // 正常情况下上一行就挡住了(满了就会关);这条是防"名额被改小"之类的意外
            throw new IllegalStateException("岗位名额已满");
        }
        filledCount++;
        if (filledCount >= headcount) {
            status = Status.CLOSED;
            closedAt = now;
            return true;
        }
        return false;
    }

    /**
     * 手动关闭。
     *
     * @return 本次是否真的发生了状态变化。已关闭的岗位再关一次不算错——
     *         重复点击、重试都会走到这里——但**不能再发一次关闭事件**,
     *         否则下游会看到同一个岗位关闭两次。
     */
    public boolean close(Instant now) {
        if (status == Status.CLOSED) {
            return false;
        }
        status = Status.CLOSED;
        closedAt = now;
        return true;
    }

    public Long getId() { return id; }
    /** @return null 表示这是个人发的 */
    public Long getOrgId() { return orgId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public long getWageCents() { return wageCents; }
    public int getHeadcount() { return headcount; }
    public int getFilledCount() { return filledCount; }
    public Status getStatus() { return status; }
    public Instant getClosedAt() { return closedAt; }
    /** @return 可能为 null —— 展示层为空时退回单位地址 */
    public String getWorkAddress() { return workAddress; }
    /** 个人发单。总价与地区必填,由数据库 CHECK 兜底。 */
    static Job byIndividual(long posterUserId, String title, String description,
                             long totalPriceCents, String regionCode, String workAddress) {
        Job j = new Job();
        j.posterUserId = posterUserId;
        j.title = title;
        j.description = description;
        j.totalPriceCents = totalPriceCents;
        j.regionCode = regionCode;
        j.workAddress = workAddress;
        // 总价模式下没有"单价"这个概念。**填 0 而不是留空** ——
        // wage_cents 是 NOT NULL,而这一单的钱由总价决定
        j.wageCents = 0;
        j.headcount = 1;
        return j;
    }

    /** @return null 表示这是单位发的 */
    public Long getPosterUserId() { return posterUserId; }
    /** @return null 表示不是总价模式 */
    public Long getTotalPriceCents() { return totalPriceCents; }
    /** @return null 表示没填地区 */
    public String getRegionCode() { return regionCode; }
}
