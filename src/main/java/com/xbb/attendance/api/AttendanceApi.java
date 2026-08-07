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

    /**
     * 计薪汇总:已确认的总工时与出勤天数。
     *
     * <p>一次拿两个值而不是调两遍 —— 两遍之间考勤可能被改,
     * 那样算出来的工资对应的是两个不同时刻的考勤,而且没人看得出来。
     *
     * @param workDays 有工时的天数(minutes > 0 才算一天)。按天计薪时用
     */
    record ConfirmedSummary(int minutes, int workDays) { }

    /**
     * <b>不带 caller —— 它是给结算域算工资用的</b>,不是对外查询。
     * 对外暴露时必须自己先判断归属(见 {@link #mayViewAttendance})。
     */
    ConfirmedSummary confirmedSummary(long applicationId);

    /**
     * 这个人能不能看这份考勤:用工方或工人本人。
     *
     * <p>2026-08-07 审计:此前 summary 端点靠 {@code listByApplication} 抛异常来挡人。
     * 把那个方法改成"无关的人拿空列表"之后,<b>这条旁路就通了</b> ——
     * 而它的注释里早写着"否则 summary 会变成一个绕过归属的旁路"。
     * 靠副作用挡人,改另一处就漏。
     */
    boolean mayViewAttendance(long applicationId, long callerUserId);
}
