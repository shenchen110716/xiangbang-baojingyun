package com.xbb.identity.internal;

import com.xbb.identity.api.IdentityApi;
import jakarta.validation.Valid;
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

    /**
     * 申请验证码。**绝不把验证码回给调用方**——这个端点是 permitAll 的,
     * 回显等于任何人报一个手机号就能登录那个账号(包括平台管理员),
     * 整套鉴权与 RBAC 直接失效。
     *
     * <p>真实短信通道尚未接入时,取码要走 {@code /api/identity/dev/code} ——
     * 那道门要口令,且只在 mock 模式装配。
     *
     * <p>守卫:{@code IdentityControllerTest#申请验证码时绝不把验证码回给调用方}
     * 与 {@code DevCodeControllerTest#公开端点仍然不回显验证码}。
     * (此处原先写的是"由 AuthenticationBoundaryTest 守着",而**那个类从来不存在**——
     * 注释点名一个守卫、读的人就当它在起作用,这正是铁律里最反复的那种失败。)
     */
    @PostMapping("/code")
    ResponseEntity<Map<String, String>> requestCode(@RequestBody @Valid PhoneRequest req) {
        codes.issue(req.phone());
        return ResponseEntity.ok(Map.of("status", "sent"));
    }

    @PostMapping("/login")
    ResponseEntity<IdentityApi.LoginResult> login(@RequestBody @Valid LoginRequest req) {
        return ResponseEntity.ok(identityApi.loginByPhone(req.phone(), req.code()));
    }

    @PutMapping("/real-name")
    ResponseEntity<Void> verify(@AuthenticationPrincipal AuthenticatedUser caller, @RequestBody @Valid RealNameRequest req) {
        // userId 来自校验过的 JWT,不信任请求体——否则任何人都能实名认证别人的账号(IDOR)
        identityApi.verifyRealName(caller.userId(), req.realName(), req.idNumber());
        return ResponseEntity.noContent().build();
    }

    // 400/409 等错误映射统一收在 com.xbb.web.GlobalExceptionHandler,不再各 Controller 各写一份
}
