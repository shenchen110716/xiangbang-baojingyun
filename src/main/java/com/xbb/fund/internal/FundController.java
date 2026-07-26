package com.xbb.fund.internal;

import com.xbb.fund.api.FundApi;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/fund")
class FundController {

    private final FundApi fundApi;

    FundController(FundApi fundApi) {
        this.fundApi = fundApi;
    }

    @GetMapping("/payouts/{id}")
    ResponseEntity<FundApi.PayoutView> get(@PathVariable long id) {
        return fundApi.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/payouts/{id}/disburse")
    ResponseEntity<Void> disburse(@PathVariable long id) {
        fundApi.disburse(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/payouts/{id}/retry")
    ResponseEntity<Void> retry(@PathVariable long id) {
        fundApi.retryDisbursement(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/payouts/{id}/disbursement")
    ResponseEntity<FundApi.DisbursementView> disbursement(@PathVariable long id) {
        return fundApi.findDisbursement(id).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/accounts/{accountType}")
    ResponseEntity<Map<String, Long>> balance(@PathVariable com.xbb.fund.api.AccountType accountType) {
        return ResponseEntity.ok(Map.of("balanceCents", fundApi.balanceOf(accountType)));
    }

    // 400/409 等错误映射统一收在 com.xbb.web.GlobalExceptionHandler
}
