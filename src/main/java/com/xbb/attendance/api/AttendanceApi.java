package com.xbb.attendance.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 考勤域(第二批)。采集出勤,供结算域计薪。
 *
 * <p>本域只负责"这个人这天干了多少" —— **不算钱**。
 * 把工时换算成工资是结算域的事,两件事分开才能各自回放:
 * 改了计薪方案要重算工资,但不该动考勤;订正了考勤要重算工资,但方案不变。
 */
public interface AttendanceApi {

    record WorkdayView(long id, long applicationId, long jobId, long workerUserId,
                       LocalDate workDate, Instant beginAt, Instant endAt, int minutes,
                       String source, String status, String remark,
                       Instant confirmedAt, Long confirmedBy) { }

    record WorkdayChangeView(long id, long workdayId, String field, String oldValue,
                             String newValue, Long changedBy, Instant changedAt, String reason) { }

    /** 一条导入/录入的结果。批量导入时逐条返回,让调用方知道哪几条没进去。 */
    record UpsertResult(long applicationId, LocalDate workDate, boolean created, String error) { }

    /**
     * 录入或更新一天的考勤。**同一履约单同一天只会有一条**(数据库唯一约束兜底)。
     *
     * <p>已确认的记录不能直接改 —— 确认过的可能已经算进工资单,
     * 静默改掉会让工资单和考勤对不上。要改先 {@link #reopen}。
     */
    long upsert(long applicationId, LocalDate workDate, int minutes,
                Instant beginAt, Instant endAt, String source, String remark,
                String reason, long callerUserId);

    /** 批量录入。逐条独立处理:一条失败不该让整批回滚,否则一个错行毁掉一整月的导入。 */
    List<UpsertResult> upsertBatch(List<BatchRow> rows, String source, String reason, long callerUserId);

    record BatchRow(long applicationId, LocalDate workDate, int minutes, String remark) { }

    void confirm(long workdayId, long callerUserId);

    /** 撤回确认。已进入工资单的考勤被撤回后,那张工资单要重算。 */
    void reopen(long workdayId, String reason, long callerUserId);

    List<WorkdayView> listByApplication(long applicationId, long callerUserId);

    List<WorkdayView> listByJob(long jobId, LocalDate from, LocalDate to, long callerUserId);

    /** 我自己的考勤。工人端用,只看得到自己的。 */
    List<WorkdayView> listMine(long workerUserId, LocalDate from, LocalDate to);

    List<WorkdayChangeView> changesOf(long workdayId, long callerUserId);

    /** 计薪用:某履约单已确认的总分钟数。 */
    int confirmedMinutes(long applicationId);
}
