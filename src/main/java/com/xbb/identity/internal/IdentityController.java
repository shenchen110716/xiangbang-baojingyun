package com.xbb.identity.internal;

import com.xbb.identity.api.IdentityApi;
import com.xbb.security.AuthenticatedUser;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/identity")
class IdentityController {

    private final IdentityApi identityApi;
    private final VerificationCodeService codes;

    IdentityController(IdentityApi identityApi, VerificationCodeService codes) {
        this.identityApi = identityApi;
        this.codes = codes;
    }

    record PhoneRequest(@NotBlank String phone) { }
    record LoginRequest(@NotBlank String phone, @NotBlank String code) { }
    record RealNameRequest(@NotBlank String realName, @NotBlank String idNumber) { }

    @PostMapping("/code")
    ResponseEntity<Map<String, String>> requestCode(@RequestBody PhoneRequest req) {
        // 开发期直接返回验证码;接入真实短信后改为只返回 ok
        return ResponseEntity.ok(Map.of("code", codes.issue(req.phone())));
    }

    @PostMapping("/login")
    ResponseEntity<IdentityApi.LoginResult> login(@RequestBody LoginRequest req) {
        return ResponseEntity.ok(identityApi.loginByPhone(req.phone(), req.code()));
    }

    @PutMapping("/real-name")
    ResponseEntity<Void> verify(@AuthenticationPrincipal AuthenticatedUser caller, @RequestBody RealNameRequest req) {
        // userId 来自校验过的 JWT,不信任请求体——否则任何人都能实名认证别人的账号(IDOR)
        identityApi.verifyRealName(caller.userId(), req.realName(), req.idNumber());
        return ResponseEntity.noContent().build();
    }

    // 400/409 等错误映射统一收在 com.xbb.web.GlobalExceptionHandler,不再各 Controller 各写一份
}
