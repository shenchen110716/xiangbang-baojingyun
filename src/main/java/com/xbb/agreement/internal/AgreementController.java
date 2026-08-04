package com.xbb.agreement.internal;

import jakarta.validation.Valid;
import com.xbb.agreement.api.AgreementApi;
import com.xbb.security.AuthenticatedUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agreement")
class AgreementController {

    private final AgreementApi agreementApi;

    AgreementController(AgreementApi agreementApi) {
        this.agreementApi = agreementApi;
    }

    record SignRequest(String identityFactor) { }

    /** 签署人一律来自 JWT,不信任请求体(IDOR 修复的既有约定)。 */
    @PutMapping("/{applicationId}/sign")
    ResponseEntity<Void> sign(@AuthenticationPrincipal AuthenticatedUser caller,
                               @PathVariable long applicationId,
                               @RequestBody @Valid SignRequest req) {
        agreementApi.sign(applicationId, caller.userId(), req.identityFactor());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{applicationId}")
    ResponseEntity<AgreementApi.AgreementView> get(@PathVariable long applicationId,
                                                    @AuthenticationPrincipal AuthenticatedUser caller) {
        return agreementApi.findByApplicationId(applicationId, caller.userId()).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // 400/409 等错误映射统一收在 com.xbb.web.GlobalExceptionHandler
}
