package com.xbb.broker.internal;

import jakarta.validation.Valid;
import com.xbb.broker.api.BrokerApi;
import com.xbb.security.AuthenticatedUser;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/broker")
class BrokerController {

    private final BrokerApi brokerApi;

    BrokerController(BrokerApi brokerApi) {
        this.brokerApi = brokerApi;
    }

    record BindRequest(@Positive long workerUserId) { }

    @PostMapping("/register")
    ResponseEntity<Void> register(@AuthenticationPrincipal AuthenticatedUser caller) {
        brokerApi.registerBroker(caller.userId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/bind")
    ResponseEntity<Map<String, Long>> bind(@AuthenticationPrincipal AuthenticatedUser caller,
                                            @RequestBody @Valid BindRequest req) {
        long invitationId = brokerApi.bindWorker(caller.userId(), req.workerUserId());
        return ResponseEntity.ok(Map.of("id", invitationId));
    }

    @GetMapping("/commission/{id}")
    ResponseEntity<BrokerApi.CommissionView> getCommission(@PathVariable long id) {
        return brokerApi.findCommission(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/commission/{id}/pay")
    ResponseEntity<Void> payCommission(@PathVariable long id,
                                        @AuthenticationPrincipal AuthenticatedUser caller) {
        brokerApi.payCommission(id, caller.userId());
        return ResponseEntity.noContent().build();
    }

    // 400/409 等错误映射统一收在 com.xbb.web.GlobalExceptionHandler
}
