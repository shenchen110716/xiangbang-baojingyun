package com.xbb.fund.internal;

import com.xbb.fund.api.FundApi;
import com.xbb.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.xbb.fund.api.AdvanceApi;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/fund")
class FundController {

    private final FundApi fundApi;
    private final com.xbb.fund.api.AdvanceApi advanceApi;

    FundController(FundApi fundApi, com.xbb.fund.api.AdvanceApi advanceApi) {
        this.advanceApi = advanceApi;
        this.fundApi = fundApi;
    }

    /** 我的发放记录。排在 /payouts/{id} 之前。 */
    @GetMapping("/payouts/mine")
    ResponseEntity<List<FundApi.PayoutView>> minePayouts(@AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.ok(fundApi.listMyPayouts(caller.userId()));
    }

    @GetMapping("/payouts/{id}")
    ResponseEntity<FundApi.PayoutView> get(@PathVariable long id,
                                            @AuthenticationPrincipal AuthenticatedUser caller) {
        return fundApi.findById(id, caller.userId()).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/payouts/{id}/disburse")
    ResponseEntity<Void> disburse(@PathVariable long id,
                                   @AuthenticationPrincipal AuthenticatedUser caller) {
        fundApi.disburse(id, caller.userId());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/payouts/{id}/retry")
    ResponseEntity<Void> retry(@PathVariable long id,
                                @AuthenticationPrincipal AuthenticatedUser caller) {
        fundApi.retryDisbursement(id, caller.userId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/payouts/{id}/disbursement")
    ResponseEntity<FundApi.DisbursementView> disbursement(@PathVariable long id,
                                                           @AuthenticationPrincipal AuthenticatedUser caller) {
        return fundApi.findDisbursement(id, caller.userId()).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    record TopUpRequest(@jakarta.validation.constraints.Positive long amountCents,
                        String reason,
                        @jakarta.validation.constraints.NotBlank String idempotencyKey) { }

    /**
     * 给监管账户入账。要 PLATFORM_OPS,且**必须带幂等键**。
     *
     * <p>此前没有这个端点,于是线上没有任何办法把钱放进监管账户,
     * 放款只会一直报"余额不足" —— 资金链路在部署形态下是断的。
     */
    @PostMapping("/accounts/{accountType}/top-up")
    ResponseEntity<Map<String, Long>> topUp(@PathVariable com.xbb.fund.api.AccountType accountType,
                                            @RequestBody @jakarta.validation.Valid TopUpRequest req,
                                            @AuthenticationPrincipal AuthenticatedUser caller) {
        fundApi.topUp(accountType, req.amountCents(),
                req.reason() == null || req.reason().isBlank() ? "平台入账" : req.reason(),
                req.idempotencyKey(), caller.userId());
        return ResponseEntity.ok(Map.of("balanceCents", fundApi.balanceOf(accountType)));
    }

    @GetMapping("/accounts/{accountType}")
    ResponseEntity<Map<String, Long>> balance(@PathVariable com.xbb.fund.api.AccountType accountType,
                                               @AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.ok(Map.of("balanceCents", fundApi.balanceOf(accountType, caller.userId())));
    }

    // ─────────────── 借支与还款(老系统 M8「借押保」) ───────────────

    record GrantRequest(
            @jakarta.validation.constraints.Positive(message = "请选择工人") long workerUserId,
            @jakarta.validation.constraints.Positive(message = "借支金额必须为正") long amountCents,
            @jakarta.validation.constraints.NotBlank(message = "请填写借支事由") String reason) { }

    record RepayRequest(
            @jakarta.validation.constraints.Positive(message = "还款金额必须为正") long amountCents) { }

    /** 批一笔借支。要平台运维 —— 这是平台垫钱,不是工人自助。 */
    @PostMapping("/advances")
    ResponseEntity<Map<String, Long>> grantAdvance(@RequestBody @jakarta.validation.Valid GrantRequest req,
                                                   @AuthenticationPrincipal AuthenticatedUser caller) {
        long id = advanceApi.grantAdvance(req.workerUserId(), req.amountCents(), req.reason(), caller.userId());
        return ResponseEntity.ok(Map.of("id", id));
    }

    /** 我的借支。**排在 /{id} 之前**,否则 "mine" 会被当成路径变量。 */
    @GetMapping("/advances/mine")
    ResponseEntity<List<AdvanceApi.AdvanceView>> myAdvances(@AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.ok(advanceApi.listMine(caller.userId()));
    }

    /** 我还欠多少。工人端在工资页显示"下次发薪会扣这些"。 */
    @GetMapping("/advances/mine/outstanding")
    ResponseEntity<Map<String, Long>> myOutstanding(@AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.ok(Map.of("outstandingCents", advanceApi.outstandingOf(caller.userId())));
    }

    /** 查某人的借支。本人或平台运维,其他人拿到空列表(不是 403 —— 见铁律 5.1)。 */
    @GetMapping("/advances/worker/{workerUserId}")
    ResponseEntity<List<AdvanceApi.AdvanceView>> advancesOf(@PathVariable long workerUserId,
                                                            @AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.ok(advanceApi.listOf(workerUserId, caller.userId()));
    }

    @GetMapping("/advances/{id}")
    ResponseEntity<AdvanceApi.AdvanceView> advance(@PathVariable long id,
                                                   @AuthenticationPrincipal AuthenticatedUser caller) {
        return advanceApi.findById(id, caller.userId())
                .map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 还款明细。争议时这是唯一说得清的东西。 */
    @GetMapping("/advances/{id}/repayments")
    ResponseEntity<List<AdvanceApi.RepaymentView>> repayments(@PathVariable long id,
                                                              @AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.ok(advanceApi.repaymentsOf(id, caller.userId()));
    }

    /** 登记线下还款。 */
    @PostMapping("/advances/{id}/repayments")
    ResponseEntity<Void> repay(@PathVariable long id, @RequestBody @jakarta.validation.Valid RepayRequest req,
                               @AuthenticationPrincipal AuthenticatedUser caller) {
        advanceApi.recordManualRepayment(id, req.amountCents(), caller.userId());
        return ResponseEntity.noContent().build();
    }

    /** 撤销。只有一分钱都没还过的才能撤。 */
    @PutMapping("/advances/{id}/cancel")
    ResponseEntity<Void> cancelAdvance(@PathVariable long id,
                                       @AuthenticationPrincipal AuthenticatedUser caller) {
        advanceApi.cancelAdvance(id, caller.userId());
        return ResponseEntity.noContent().build();
    }

    // 400/409 等错误映射统一收在 com.xbb.web.GlobalExceptionHandler
}
