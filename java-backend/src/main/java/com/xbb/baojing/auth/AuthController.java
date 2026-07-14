package com.xbb.baojing.auth;

import com.xbb.baojing.common.ApiException;
import com.xbb.baojing.common.AuditService;
import com.xbb.baojing.common.JwtService;
import com.xbb.baojing.common.User;
import com.xbb.baojing.common.UserMapper;
import com.xbb.baojing.enterprise.Enterprise;
import com.xbb.baojing.enterprise.EnterpriseMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final UserMapper userMapper;
    private final EnterpriseMapper enterpriseMapper;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public AuthController(UserMapper userMapper, EnterpriseMapper enterpriseMapper, JwtService jwtService, PasswordEncoder passwordEncoder, AuditService auditService) {
        this.userMapper = userMapper;
        this.enterpriseMapper = enterpriseMapper;
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

    public record LinkedAccountOut(int id, String name, Integer enterpriseId, String enterpriseName) {}

    /** Other enterprise-owner accounts sharing this user's phone number — the
     * real-world case being one person who is 负责人 for multiple 参保单位,
     * each with its own separate account (feedback item 11). Lets the web app
     * offer an in-app switcher instead of requiring logout/login per company. */
    private List<User> linkedAccounts(User user) {
        if (!"enterprise".equals(user.getRole()) || !user.isOwner() || user.getPhone() == null || user.getPhone().isBlank()) return List.of();
        return userMapper.findLinkedOwnersByPhone(user.getPhone(), user.getId());
    }

    @GetMapping("/linked-accounts")
    public List<LinkedAccountOut> getLinkedAccounts(User user) {
        return linkedAccounts(user).stream().map(item -> {
            Enterprise enterprise = item.getEnterpriseId() != null ? enterpriseMapper.findById(item.getEnterpriseId()) : null;
            return new LinkedAccountOut(item.getId(), item.getName(), item.getEnterpriseId(), enterprise != null ? enterprise.getName() : "");
        }).toList();
    }

    @PostMapping("/switch-account")
    public TokenOut switchAccount(@RequestParam("target_user_id") int targetUserId, User user) {
        User target = linkedAccounts(user).stream().filter(item -> item.getId() == targetUserId).findFirst()
                .orElseThrow(() -> ApiException.forbidden("无权切换到该账号"));
        auditService.log(user, "switch_account", "user", String.valueOf(target.getId()));
        return new TokenOut(jwtService.issueToken(target.getId(), target.getSessionVersion()), "bearer");
    }

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
