package com.xbb.fund.internal;

import com.xbb.fund.api.FundApi;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    // 400/409 等错误映射统一收在 com.xbb.web.GlobalExceptionHandler
}
