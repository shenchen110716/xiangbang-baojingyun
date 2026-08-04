package com.xbb.attendance.internal;

import com.xbb.attendance.api.AttendanceApi;
import com.xbb.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 考勤的 HTTP 口子。
 *
 * <p>考勤域此前只有 API 没有控制器 —— 工时录不进来,计薪就永远走"没有考勤"那条退回分支,
 * 界面上看不出任何异常,工资却是按岗位一口价发的。**做出来但接不上等于没做。**
 *
 * <p>归属校验一律在服务层(它持有 engaged_worker 副本),这里只负责搬运和参数校验。
 */
@RestController
@RequestMapping("/api/attendance")
class AttendanceController {

    private final AttendanceApi attendanceApi;

    AttendanceController(AttendanceApi attendanceApi) {
        this.attendanceApi = attendanceApi;
    }

    record UpsertRequest(
            @Positive(message = "履约单不能为空") long applicationId,
            @NotNull(message = "请选择日期") LocalDate workDate,
            @PositiveOrZero(message = "工时不能为负") int minutes,
            Instant beginAt,
            Instant endAt,
            String source,
            @Size(max = 200, message = "备注最多 200 字") String remark,
            @NotBlank(message = "请填写录入原因") String reason) { }

    record BatchRequest(
            @NotNull @Size(min = 1, max = 500, message = "一次最多导入 500 条") List<BatchItem> rows,
            String source,
            @NotBlank(message = "请填写导入原因") String reason) { }

    record BatchItem(long applicationId, LocalDate workDate, int minutes, String remark) { }

    record ReasonRequest(@NotBlank(message = "请填写原因") String reason) { }

    @PostMapping
    ResponseEntity<Map<String, Long>> upsert(@RequestBody @Valid UpsertRequest req,
                                             @AuthenticationPrincipal AuthenticatedUser caller) {
        long id = attendanceApi.upsert(req.applicationId(), req.workDate(), req.minutes(),
                req.beginAt(), req.endAt(),
                req.source() == null || req.source().isBlank() ? "MANUAL" : req.source(),
                req.remark(), req.reason(), caller.userId());
        return ResponseEntity.ok(Map.of("id", id));
    }

    /**
     * 批量录入。**整体返回 200,逐条带 error** —— 一条失败不该让整批回滚,
     * 否则一个错行毁掉一整月的导入,而人还得自己找出是哪一行。
     */
    @PostMapping("/batch")
    ResponseEntity<List<AttendanceApi.UpsertResult>> batch(@RequestBody @Valid BatchRequest req,
                                                           @AuthenticationPrincipal AuthenticatedUser caller) {
        List<AttendanceApi.BatchRow> rows = req.rows().stream()
                .map(r -> new AttendanceApi.BatchRow(r.applicationId(), r.workDate(), r.minutes(), r.remark()))
                .toList();
        return ResponseEntity.ok(attendanceApi.upsertBatch(rows,
                req.source() == null || req.source().isBlank() ? "IMPORT" : req.source(),
                req.reason(), caller.userId()));
    }

    @PutMapping("/{id}/confirm")
    ResponseEntity<Void> confirm(@PathVariable long id, @AuthenticationPrincipal AuthenticatedUser caller) {
        attendanceApi.confirm(id, caller.userId());
        return ResponseEntity.noContent().build();
    }

    /** 撤回确认。撤回后已出的工资单要重算 —— 所以强制填原因,事后查得到是谁改的。 */
    @PutMapping("/{id}/reopen")
    ResponseEntity<Void> reopen(@PathVariable long id, @RequestBody @Valid ReasonRequest req,
                                @AuthenticationPrincipal AuthenticatedUser caller) {
        attendanceApi.reopen(id, req.reason(), caller.userId());
        return ResponseEntity.noContent().build();
    }

    /** 我自己的考勤。**排在 /{...} 之前**,否则 "mine" 会被当成路径变量。 */
    @GetMapping("/mine")
    ResponseEntity<List<AttendanceApi.WorkdayView>> mine(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate begin = from == null ? end.minusMonths(1) : from;
        return ResponseEntity.ok(attendanceApi.listMine(caller.userId(), begin, end));
    }

    @GetMapping("/application/{applicationId}")
    ResponseEntity<List<AttendanceApi.WorkdayView>> byApplication(
            @PathVariable long applicationId, @AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.ok(attendanceApi.listByApplication(applicationId, caller.userId()));
    }

    @GetMapping("/job/{jobId}")
    ResponseEntity<List<AttendanceApi.WorkdayView>> byJob(
            @PathVariable long jobId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate begin = from == null ? end.minusMonths(1) : from;
        return ResponseEntity.ok(attendanceApi.listByJob(jobId, begin, end, caller.userId()));
    }

    /** 订正轨迹。工时改过几次、谁改的、为什么 —— 争议时这是唯一说得清的东西。 */
    @GetMapping("/{id}/changes")
    ResponseEntity<List<AttendanceApi.WorkdayChangeView>> changes(
            @PathVariable long id, @AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.ok(attendanceApi.changesOf(id, caller.userId()));
    }

    /** 计薪汇总:已确认工时与出勤天数。企业端在发工资前拿它对账。 */
    @GetMapping("/application/{applicationId}/summary")
    ResponseEntity<AttendanceApi.ConfirmedSummary> summary(@PathVariable long applicationId,
                                                           @AuthenticationPrincipal AuthenticatedUser caller) {
        // 归属校验借道 listByApplication:没权限的人在这里就会被挡下,
        // 否则 summary 会变成一个绕过归属的旁路 —— 数字虽小,也是别人的工时
        attendanceApi.listByApplication(applicationId, caller.userId());
        return ResponseEntity.ok(attendanceApi.confirmedSummary(applicationId));
    }

    // 400/403/409 的映射统一收在 com.xbb.web.GlobalExceptionHandler
}
