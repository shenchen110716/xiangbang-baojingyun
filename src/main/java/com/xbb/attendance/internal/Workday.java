package com.xbb.attendance.internal;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 某人在某个履约单下、某一天的出勤。
 *
 * <p><b>一个履约单一天只有一条</b>(数据库唯一约束)。这是防重复计薪的根:
 * 少了它,导入两次就是两份工时、工资直接翻倍,而账面上看不出异常 ——
 * 两条记录各自都是合法的。
 */
@Entity
@Table(name = "workday", schema = "attendance")
public class Workday {

    /** 记来源是为了出账疑问时能回答"这条是怎么来的"。 */
    public enum Source { PUNCH, IMPORT, MANUAL }

    /** 只有 CONFIRMED 的才进入计薪。 */
    public enum Status { DRAFT, CONFIRMED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false)
    private long applicationId;

    @Column(name = "job_id", nullable = false)
    private long jobId;

    @Column(name = "worker_user_id", nullable = false)
    private long workerUserId;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Column(name = "begin_at")
    private Instant beginAt;

    @Column(name = "end_at")
    private Instant endAt;

    /** 工时用分钟存整数。小时用小数会引入浮点,而它要参与工资计算。 */
    @Column(nullable = false)
    private int minutes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Source source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.DRAFT;

    @Column(length = 200)
    private String remark;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "confirmed_by")
    private Long confirmedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Version
    private long version;

    protected Workday() { }

    Workday(long applicationId, long jobId, long workerUserId, LocalDate workDate,
            int minutes, Source source) {
        this.applicationId = applicationId;
        this.jobId = jobId;
        this.workerUserId = workerUserId;
        this.workDate = workDate;
        this.minutes = minutes;
        this.source = source;
    }

    public Long getId() { return id; }
    public long getApplicationId() { return applicationId; }
    public long getJobId() { return jobId; }
    public long getWorkerUserId() { return workerUserId; }
    public LocalDate getWorkDate() { return workDate; }
    public Instant getBeginAt() { return beginAt; }
    public Instant getEndAt() { return endAt; }
    public int getMinutes() { return minutes; }
    public Source getSource() { return source; }
    public Status getStatus() { return status; }
    public String getRemark() { return remark; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public Long getConfirmedBy() { return confirmedBy; }

    void setTimes(Instant beginAt, Instant endAt) {
        if ((beginAt == null) != (endAt == null)) {
            throw new IllegalArgumentException("上下班时间要么都填,要么都不填");
        }
        if (beginAt != null && endAt.isBefore(beginAt)) {
            throw new IllegalArgumentException("下班时间不能早于上班时间");
        }
        this.beginAt = beginAt;
        this.endAt = endAt;
    }

    /**
     * 改工时。**已确认的不能直接改** —— 确认过的考勤可能已经算进工资单,
     * 静默改掉会让工资单和考勤对不上,而且没人知道差在哪。要改先撤回确认。
     */
    void changeMinutes(int minutes) {
        requireEditable();
        if (minutes < 0 || minutes > 1440) {
            throw new IllegalArgumentException("单日工时必须在 0 到 1440 分钟之间");
        }
        this.minutes = minutes;
    }

    void setRemark(String remark) {
        requireEditable();
        this.remark = remark;
    }

    void confirm(long operatorUserId) {
        if (status == Status.CONFIRMED) {
            return;   // 幂等:重复确认不是错误
        }
        this.status = Status.CONFIRMED;
        this.confirmedAt = Instant.now();
        this.confirmedBy = operatorUserId;
    }

    /** 撤回确认。必须显式做,不能靠改字段顺带发生。 */
    void reopen() {
        this.status = Status.DRAFT;
        this.confirmedAt = null;
        this.confirmedBy = null;
    }

    private void requireEditable() {
        if (status == Status.CONFIRMED) {
            throw new IllegalStateException("考勤已确认,要修改请先撤回确认");
        }
    }
}
