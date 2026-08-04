package com.xbb.attendance.internal;

import com.xbb.attendance.api.AttendanceApi;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
class AttendanceService implements AttendanceApi {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AttendanceService.class);

    private final WorkdayRepository workdays;
    private final WorkdayChangeRepository changes;
    private final EngagedWorkerRepository engaged;
    private final AttendanceApprovedOrgRepository orgs;
    private final ObjectProvider<AttendanceService> self;

    AttendanceService(WorkdayRepository workdays, WorkdayChangeRepository changes,
                      EngagedWorkerRepository engaged, AttendanceApprovedOrgRepository orgs,
                      ObjectProvider<AttendanceService> self) {
        this.workdays = workdays;
        this.changes = changes;
        this.engaged = engaged;
        this.orgs = orgs;
        this.self = self;
    }

    @Override
    @Transactional("attendanceTransactionManager")
    public long upsert(long applicationId, LocalDate workDate, int minutes,
                       Instant beginAt, Instant endAt, String source, String remark,
                       String reason, long callerUserId) {
        EngagedWorker ew = requireEngaged(applicationId);
        requireEmployer(ew, callerUserId);
        requireReason(reason);

        Workday.Source src = parseSource(source);
        Workday existing = workdays.findByApplicationIdAndWorkDate(applicationId, workDate).orElse(null);

        if (existing == null) {
            Workday w = new Workday(applicationId, ew.getJobId(), ew.getWorkerUserId(),
                    workDate, guardMinutes(minutes), src);
            w.setTimes(beginAt, endAt);
            w.setRemark(remark);
            Workday saved = workdays.save(w);
            changes.save(new WorkdayChange(saved.getId(), "minutes", null,
                    String.valueOf(minutes), callerUserId, reason));
            return saved.getId();
        }

        int old = existing.getMinutes();
        existing.changeMinutes(guardMinutes(minutes));   // 已确认的会在这里被拒
        existing.setTimes(beginAt, endAt);
        existing.setRemark(remark);
        workdays.save(existing);
        if (old != minutes) {
            changes.save(new WorkdayChange(existing.getId(), "minutes",
                    String.valueOf(old), String.valueOf(minutes), callerUserId, reason));
        }
        return existing.getId();
    }

    @Override
    public List<UpsertResult> upsertBatch(List<BatchRow> rows, String source,
                                          String reason, long callerUserId) {
        // **逐条独立事务**:一条失败不该让整批回滚。
        // 导入一个月的考勤时,一个错行毁掉整批是最难受的失败方式 ——
        // 运营不知道哪条错,只能全部重来。
        List<UpsertResult> results = new ArrayList<>(rows.size());
        for (BatchRow row : rows) {
            try {
                self.getObject().upsert(row.applicationId(), row.workDate(), row.minutes(),
                        null, null, source, row.remark(), reason, callerUserId);
                results.add(new UpsertResult(row.applicationId(), row.workDate(), true, null));
            } catch (RuntimeException e) {
                results.add(new UpsertResult(row.applicationId(), row.workDate(), false, e.getMessage()));
            }
        }
        long failed = results.stream().filter(r -> r.error() != null).count();
        if (failed > 0) {
            log.warn("考勤批量导入:{} 条成功,{} 条失败", results.size() - failed, failed);
        }
        return results;
    }

    @Override
    @Transactional("attendanceTransactionManager")
    public void confirm(long workdayId, long callerUserId) {
        Workday w = requireWorkday(workdayId);
        requireEmployer(requireEngaged(w.getApplicationId()), callerUserId);
        if (w.getStatus() == Workday.Status.CONFIRMED) {
            return;   // 幂等
        }
        w.confirm(callerUserId);
        workdays.save(w);
        changes.save(new WorkdayChange(workdayId, "status", "DRAFT", "CONFIRMED",
                callerUserId, "确认考勤"));
    }

    @Override
    @Transactional("attendanceTransactionManager")
    public void reopen(long workdayId, String reason, long callerUserId) {
        requireReason(reason);
        Workday w = requireWorkday(workdayId);
        requireEmployer(requireEngaged(w.getApplicationId()), callerUserId);
        if (w.getStatus() == Workday.Status.DRAFT) {
            return;
        }
        w.reopen();
        workdays.save(w);
        changes.save(new WorkdayChange(workdayId, "status", "CONFIRMED", "DRAFT",
                callerUserId, reason));
        // 撤回确认意味着已经算过的工资单可能不对了。这里只记日志不自动重算:
        // 重算是结算域的决定,考勤域不该替它做主。
        log.warn("考勤 {} 被撤回确认,若已进入工资单需要重算。操作人={} 理由={}",
                workdayId, callerUserId, reason);
    }

    @Override
    @Transactional(transactionManager = "attendanceTransactionManager", readOnly = true)
    public List<WorkdayView> listByApplication(long applicationId, long callerUserId) {
        EngagedWorker ew = requireEngaged(applicationId);
        // 用工方或工人本人都能看
        if (ew.getWorkerUserId() != callerUserId) {
            requireEmployer(ew, callerUserId);
        }
        return workdays.findByApplicationIdOrderByWorkDateAsc(applicationId).stream()
                .map(AttendanceService::toView).toList();
    }

    @Override
    @Transactional(transactionManager = "attendanceTransactionManager", readOnly = true)
    public List<WorkdayView> listByJob(long jobId, LocalDate from, LocalDate to, long callerUserId) {
        List<Workday> rows = workdays
                .findByJobIdAndWorkDateBetweenOrderByWorkDateAscWorkerUserIdAsc(jobId, from, to);
        if (rows.isEmpty()) {
            return List.of();
        }
        // 这个岗位下任取一条,用它的履约单校验归属 —— 同一岗位的组织是同一个
        requireEmployer(requireEngaged(rows.get(0).getApplicationId()), callerUserId);
        return rows.stream().map(AttendanceService::toView).toList();
    }

    @Override
    @Transactional(transactionManager = "attendanceTransactionManager", readOnly = true)
    public List<WorkdayView> listMine(long workerUserId, LocalDate from, LocalDate to) {
        // 查询条件即归属:只查"工人是我"的行,不存在越权看到别人考勤的路径
        return workdays.findByWorkerUserIdAndWorkDateBetweenOrderByWorkDateDesc(workerUserId, from, to)
                .stream().map(AttendanceService::toView).toList();
    }

    @Override
    @Transactional(transactionManager = "attendanceTransactionManager", readOnly = true)
    public List<WorkdayChangeView> changesOf(long workdayId, long callerUserId) {
        Workday w = requireWorkday(workdayId);
        requireEmployer(requireEngaged(w.getApplicationId()), callerUserId);
        return changes.findByWorkdayIdOrderByChangedAtDesc(workdayId).stream()
                .map(c -> new WorkdayChangeView(c.getId(), c.getWorkdayId(), c.getField(),
                        c.getOldValue(), c.getNewValue(), c.getChangedBy(), c.getChangedAt(), c.getReason()))
                .toList();
    }

    @Override
    @Transactional(transactionManager = "attendanceTransactionManager", readOnly = true)
    public int confirmedMinutes(long applicationId) {
        // **只算已确认的。** 草稿态的考勤还可能被订正,拿它计薪等于按未定稿的数字发钱。
        return workdays.findByApplicationIdAndStatus(applicationId, Workday.Status.CONFIRMED)
                .stream().mapToInt(Workday::getMinutes).sum();
    }

    // ── 私有 ──

    private EngagedWorker requireEngaged(long applicationId) {
        return engaged.findById(applicationId).orElseThrow(() -> new IllegalArgumentException(
                "履约单不存在或尚未录用,不能录考勤: " + applicationId));
    }

    private Workday requireWorkday(long workdayId) {
        return workdays.findById(workdayId)
                .orElseThrow(() -> new IllegalArgumentException("考勤记录不存在: " + workdayId));
    }

    /** 归属校验:只有岗位所属组织的法人代表能录/改/确认考勤。 */
    private void requireEmployer(EngagedWorker ew, long callerUserId) {
        ApprovedOrg org = orgs.findById(ew.getOrgId())
                .orElseThrow(() -> new IllegalStateException("组织未通过审核"));
        if (org.getLegalRepUserId() != callerUserId) {
            throw new IllegalStateException("只有组织法人代表可以操作考勤");
        }
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("必须填写录入/变更理由");
        }
    }

    private static int guardMinutes(int minutes) {
        if (minutes < 0 || minutes > 1440) {
            throw new IllegalArgumentException("单日工时必须在 0 到 1440 分钟之间");
        }
        return minutes;
    }

    private static Workday.Source parseSource(String source) {
        try {
            return Workday.Source.valueOf(source == null ? "MANUAL" : source.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的考勤来源: " + source);
        }
    }

    private static WorkdayView toView(Workday w) {
        return new WorkdayView(w.getId(), w.getApplicationId(), w.getJobId(), w.getWorkerUserId(),
                w.getWorkDate(), w.getBeginAt(), w.getEndAt(), w.getMinutes(),
                w.getSource().name(), w.getStatus().name(), w.getRemark(),
                w.getConfirmedAt(), w.getConfirmedBy());
    }
}
