package com.xbb.baojing.auth;

import com.xbb.baojing.common.ApiException;
import com.xbb.baojing.common.AuditService;
import com.xbb.baojing.common.JwtService;
import com.xbb.baojing.common.User;
import com.xbb.baojing.common.UserMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final UserMapper userMapper;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public AuthController(UserMapper userMapper, JwtService jwtService, PasswordEncoder passwordEncoder, AuditService auditService) {
        this.userMapper = userMapper;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    public record LoginIn(String username, String password, String portal) {}
    public record TokenOut(String accessToken, String tokenType) {}
    public record PasswordChangeIn(String currentPassword, String newPassword) {}

    @PostMapping("/login")
    public TokenOut login(@RequestBody LoginIn data) {
        User user = userMapper.findByUsername(data.username());
        if (user == null || !passwordEncoder.matches(data.password(), user.getPasswordHash())) throw ApiException.unauthorized("账号或密码错误");
        if (!user.isActive()) throw ApiException.forbidden("该账号已停用，请联系单位主管");
        String portal = data.portal() == null ? "admin" : data.portal();
        if ("admin".equals(portal) && !"admin".equals(user.getRole())) throw ApiException.forbidden("该账号不是总后台账号");
        if ("enterprise".equals(portal) && !"enterprise".equals(user.getRole())) throw ApiException.forbidden("该账号不是参保单位账号");
        String token = jwtService.issueToken(user.getId(), user.getSessionVersion());
        return new TokenOut(token, "bearer");
    }

    @GetMapping("/me")
    public User me(User user) { return user; }

    @PatchMapping("/password")
    public java.util.Map<String, Boolean> changePassword(@RequestBody PasswordChangeIn data, User user) {
        if (!passwordEncoder.matches(data.currentPassword(), user.getPasswordHash())) throw ApiException.badRequest("当前密码不正确");
        if (data.newPassword() == null || data.newPassword().length() < 6) throw ApiException.badRequest("新密码至少 6 位");
        if (data.currentPassword().equals(data.newPassword())) throw ApiException.badRequest("新密码不能与当前密码相同");
        user.setPasswordHash(passwordEncoder.encode(data.newPassword()));
        user.setSessionVersion(user.getSessionVersion() + 1);
        userMapper.update(user);
        auditService.log(user, "password_change", "user", String.valueOf(user.getId()));
        return java.util.Map.of("ok", true);
    }
}
