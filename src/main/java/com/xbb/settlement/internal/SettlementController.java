package com.xbb.settlement.internal;

import com.xbb.settlement.api.SettlementApi;
import jakarta.validation.constraints.NotBlank;
import com.xbb.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/settlement")
class SettlementController {

    private final SettlementApi settlementApi;

    SettlementController(SettlementApi settlementApi) {
        this.settlementApi = settlementApi;
    }

    record VoidRequest(@NotBlank String reason) { }

    @GetMapping("/{id}")
    ResponseEntity<SettlementApi.SettlementView> get(@PathVariable long id) {
        return settlementApi.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}/void")
    ResponseEntity<Void> voidSettlement(@PathVariable long id, @RequestBody VoidRequest req,
                                         @AuthenticationPrincipal AuthenticatedUser caller) {
        settlementApi.voidSettlement(id, req.reason(), caller.userId());
        return ResponseEntity.noContent().build();
    }

    // 400/409 等错误映射统一收在 com.xbb.web.GlobalExceptionHandler
}
