package com.xbb.fund.internal;

import com.xbb.fund.api.FundApi;
import com.xbb.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/fund")
class FundController {

    private final FundApi fundApi;

    FundController(FundApi fundApi) {
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

    // 400/409 等错误映射统一收在 com.xbb.web.GlobalExceptionHandler
}
