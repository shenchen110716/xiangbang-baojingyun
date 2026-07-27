package com.xbb.engagement.internal;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "application", schema = "engagement")
public class Application {

    public enum Status { PENDING, ACCEPTED, REJECTED, COMPLETED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private long jobId;

    @Column(name = "applicant_user_id", nullable = false)
    private long applicantUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    // 乐观锁:同 Organization 的 TOCTOU 修复,防止并发 accept/reject 都成功
    @Version
    private long version;

    protected Application() { }

    public Application(long jobId, long applicantUserId) {
        this.jobId = jobId;
        this.applicantUserId = applicantUserId;
    }

    /**
     * 只读的可录用检查。
     *
     * <p>单独拆出来是因为**扣名额发生在跨域事务里,一旦提交就回滚不了**——
     * 状态不对必须在扣名额之前就拦住,不能等 accept() 时才发现。
     */
    public void requireAcceptable() {
        if (status != Status.PENDING) throw new IllegalStateException("只有待处理状态可以处理");
    }

    public void accept() {
        requireAcceptable();
        this.status = Status.ACCEPTED;
    }

    public void reject() {
        if (status != Status.PENDING) throw new IllegalStateException("只有待处理状态可以处理");
        this.status = Status.REJECTED;
    }

    /**
     * 履约完成。这里只是"法人代表确认干完了"这一个状态转换,**没有**到岗/打卡/考勤记录支撑——
     * 考勤是独立的基础设施,评价飞轮不需要它。有了这个终态,§5.3 R1"只有完成的履约单可评"
     * 才有可绑定的锚点。
     */
    public void complete() {
        if (status != Status.ACCEPTED) throw new IllegalStateException("只有已录用状态可以完成");
        this.status = Status.COMPLETED;
    }

    public Long getId() { return id; }
    public long getJobId() { return jobId; }
    public long getApplicantUserId() { return applicantUserId; }
    public Status getStatus() { return status; }
}
