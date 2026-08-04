package com.xbb.settlement.internal;

import jakarta.validation.Valid;
import com.xbb.settlement.api.SettlementApi;
import jakarta.validation.constraints.NotBlank;
import com.xbb.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

    // 400/409 等错误映射统一收在 com.xbb.web.GlobalExceptionHandler
}
