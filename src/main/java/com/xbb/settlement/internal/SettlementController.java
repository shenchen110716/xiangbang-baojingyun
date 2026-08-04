package com.xbb.settlement.internal;

import jakarta.validation.Valid;
import com.xbb.settlement.api.SettlementApi;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import com.xbb.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/settlement")
class SettlementController {

    private final SettlementApi settlementApi;

    SettlementController(SettlementApi settlementApi) {
        this.settlementApi = settlementApi;
    }

    record VoidRequest(@NotBlank String reason) { }

    /** 我的工资单。排在 /{id} 之前。 */
    @GetMapping("/mine")
    ResponseEntity<List<SettlementApi.SettlementView>> mine(@AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.ok(settlementApi.listMySettlements(caller.userId()));
    }

    @GetMapping("/{id}")
    ResponseEntity<SettlementApi.SettlementView> get(@PathVariable long id) {
        return settlementApi.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}/void")
    ResponseEntity<Void> voidSettlement(@PathVariable long id, @RequestBody @Valid VoidRequest req,
                                         @AuthenticationPrincipal AuthenticatedUser caller) {
        settlementApi.voidSettlement(id, req.reason(), caller.userId());
        return ResponseEntity.noContent().build();
    }

    // ─────────────── 计薪方案 ───────────────

    record FactorRequest(
            @NotBlank(message = "请选择调整项类型") String factorType,
            @NotBlank(message = "请填写调整项名称") String name,
            @Positive(message = "调整项金额必须为正数,方向由类型决定") long amountCents) { }

    record PublishPlanRequest(
            @NotBlank(message = "请填写方案名称") String name,
            @NotBlank(message = "请选择计薪方式") String payType,
            @PositiveOrZero(message = "基本工资不能为负") long basicSalaryCents,
            @PositiveOrZero(message = "浮动工资不能为负") long floatSalaryCents,
            @PositiveOrZero(message = "固定工资不能为负") long fixedSalaryCents,
            @NotNull(message = "请选择生效日期") LocalDate effectiveFrom,
            List<FactorRequest> factors) { }

    /**
     * 发布方案。**每次都是发新版本**,旧版自动失效但不删除 ——
     * 已出的工资单还要靠它解释金额。
     */
    @PostMapping("/job/{jobId}/pay-plan")
    ResponseEntity<Map<String, Long>> publishPlan(@PathVariable long jobId,
                                                  @RequestBody @Valid PublishPlanRequest req,
                                                  @AuthenticationPrincipal AuthenticatedUser caller) {
        List<SettlementApi.FactorSpec> factors = req.factors() == null ? List.of()
                : req.factors().stream()
                    .map(f -> new SettlementApi.FactorSpec(f.factorType(), f.name(), f.amountCents()))
                    .toList();
        long id = settlementApi.publishPayPlan(jobId, req.name(), req.payType(),
                req.basicSalaryCents(), req.floatSalaryCents(), req.fixedSalaryCents(),
                req.effectiveFrom(), factors, caller.userId());
        return ResponseEntity.ok(Map.of("id", id));
    }

    /** 岗位的全部方案版本,新的在前。历史版本要看得见,否则解释不了旧工资单。 */
    @GetMapping("/job/{jobId}/pay-plans")
    ResponseEntity<List<SettlementApi.PayPlanView>> listPlans(@PathVariable long jobId,
                                                              @AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.ok(settlementApi.listPayPlans(jobId, caller.userId()));
    }

    /** 当前生效的方案。没有则 204 —— 不是错误,表示这个岗位还按岗位工价一口价发。 */
    @GetMapping("/job/{jobId}/pay-plan/active")
    ResponseEntity<SettlementApi.PayPlanView> activePlan(@PathVariable long jobId,
                                                          @AuthenticationPrincipal AuthenticatedUser caller) {
        return settlementApi.activePayPlan(jobId, caller.userId())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    // 400/409 等错误映射统一收在 com.xbb.web.GlobalExceptionHandler
}
