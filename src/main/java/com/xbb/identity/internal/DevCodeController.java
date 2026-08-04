package com.xbb.identity.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 取验证码的开发端点。**只在 xbb.channel.mode=mock 时装配**,且必须带正确口令。
 *
 * <p>为什么不直接让 {@code /api/identity/code} 把验证码回给调用方:那个端点是 permitAll 的,
 * 回显等于任何人报一个手机号就能登录那个账号(**包括平台管理员**),整套鉴权与 RBAC 直接失效。
 * 这个洞在这个项目里**真的存在过并被修掉了**,`IdentityControllerTest` 里那条
 * {@code jsonPath("$.code").doesNotExist()} 就是当时留下的守卫,不能为了图方便再打开。
 *
 * <p>这里换的思路是:公开端点一个字不动,另开一道**要口令**的门。
 * 口令只有部署者有,没口令的人拿不到码,于是"公网可达"不再等于"谁都能登任意账号"。
 *
 * <p>真实短信通道接入后,这个类连同 {@code xbb.dev.code-token} 一起删掉。
 */
@RestController
@RequestMapping("/api/identity/dev")
@ConditionalOnProperty(name = "xbb.channel.mode", havingValue = "mock")
class DevCodeController {

    private final VerificationCodeService codes;
    private final String token;

    DevCodeController(VerificationCodeService codes,
                      @Value("${xbb.dev.code-token:}") String token) {
        this.codes = codes;
        this.token = token;
    }

    record DevCodeRequest(@NotBlank String phone) { }

    @PostMapping("/code")
    ResponseEntity<Map<String, String>> devCode(
            @RequestHeader(value = "X-Dev-Token", required = false) String supplied,
            @RequestBody @Valid DevCodeRequest req) {

        // 口令没配(或配成空串)时这道门**整个关掉**。
        // 铁律 6 栽过一次:Spring 会拒绝"未设置"的变量,但空字符串是合法值,
        // 于是空口令能启动、而且能匹配上调用方传来的空口令——等于没有门。
        // 一律回 404 而不是 401/403:不确认这个端点存在,少给探测者一点信息。
        //
        // 用 ResponseEntity 直接返回,**不抛 ResponseStatusException** ——
        // 抛异常会让 Spring Boot 转发到 /error,而 /error 要求认证,
        // 匿名调用方最终收到的是 401 而不是 404。MockMvc 不走那条转发,
        // 所以单元测试看着是对的、线上是另一个码。本地起容器实测才发现。
        if (token == null || token.isBlank()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (supplied == null || !constantTimeEquals(supplied, token)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok(Map.of("code", codes.issue(req.phone())));
    }

    /** 定长比较,避免按字符逐位比较时的时序泄漏。 */
    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
