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

    @Column(name = "org_id", nullable = false)
    private long orgId;

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
    public long getOrgId() { return orgId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public long getWageCents() { return wageCents; }
    public int getHeadcount() { return headcount; }
    public int getFilledCount() { return filledCount; }
    public Status getStatus() { return status; }
    public Instant getClosedAt() { return closedAt; }
    /** @return 可能为 null —— 展示层为空时退回单位地址 */
    public String getWorkAddress() { return workAddress; }
}
