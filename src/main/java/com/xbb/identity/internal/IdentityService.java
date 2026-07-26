package com.xbb.identity.internal;

import com.xbb.identity.api.IdentityApi;
import com.xbb.identity.api.Role;
import com.xbb.identity.api.UserRegistered;
import com.xbb.identity.api.UserVerified;
import com.xbb.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
class IdentityService implements IdentityApi {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(IdentityService.class);

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private final SecureRandom random = new SecureRandom();

    private final UserRepository users;
    private final UserRoleRepository roles;
    private final VerificationCodeService codes;
    private final JwtService jwt;
    private final IdentityOutboxRepository outbox;
    private final ObjectMapper json;

    IdentityService(UserRepository users, UserRoleRepository roles, VerificationCodeService codes,
                    JwtService jwt, IdentityOutboxRepository outbox, ObjectMapper json) {
        this.users = users;
        this.roles = roles;
        this.codes = codes;
        this.jwt = jwt;
        this.outbox = outbox;
        this.json = json;
    }

    private String serialize(Object event) {
        try {
            return json.writeValueAsString(event);
        } catch (Exception e) {
            // 序列化不了就别让这步业务成功——事件发不出去,下游永远补不回来
            throw new IllegalStateException("事件无法序列化: " + event, e);
        }
    }

    /**
     * 引导管理员的手机号,逗号分隔,**默认为空**。
     *
     * <p>授权链需要一个根:第一个 PLATFORM_ADMIN 不可能由别的管理员授予。
     * 用配置而不是代码或迁移脚本来指定,是因为这份名单在不同环境必然不同,
     * 而且它属于部署决策,不该写死在仓库里。默认空 = 谁都不是管理员,失败关闭。
     */
    @Value("${xbb.security.bootstrap-admin-phones:}")
    private String bootstrapAdminPhones;

    private void grantBootstrapAdminIfListed(String phone, long userId) {
        boolean listed = java.util.Arrays.stream(bootstrapAdminPhones.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .anyMatch(phone::equals);
        if (listed && !roles.existsByUserIdAndRole(userId, Role.PLATFORM_ADMIN)) {
            roles.save(new UserRole(userId, Role.PLATFORM_ADMIN));
            log.warn("按引导名单授予平台管理员。userId={} phone={}", userId, phone);
        }
    }

    @Override
    @Transactional(transactionManager = "identityTransactionManager", readOnly = true)
    public Set<Role> rolesOf(long userId) {
        return roles.findByUserId(userId).stream().map(UserRole::getRole)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    @Transactional(transactionManager = "identityTransactionManager", readOnly = true)
    public boolean hasRole(long userId, Role role) {
        return roles.existsByUserIdAndRole(userId, role);
    }

    @Override
    @Transactional("identityTransactionManager")
    public void grantRole(long targetUserId, Role role, long callerUserId) {
        requirePlatformAdmin(callerUserId);
        if (users.findById(targetUserId).isEmpty()) {
            throw new IllegalArgumentException("用户不存在");
        }
        if (!roles.existsByUserIdAndRole(targetUserId, role)) {
            roles.save(new UserRole(targetUserId, role));
            log.info("授予角色。target={} role={} by={}", targetUserId, role, callerUserId);
        }
    }

    @Override
    @Transactional("identityTransactionManager")
    public void revokeRole(long targetUserId, Role role, long callerUserId) {
        requirePlatformAdmin(callerUserId);
        // 不允许收回自己最后的管理员权限:否则一次误操作就没人能再改角色了
        if (role == Role.PLATFORM_ADMIN && targetUserId == callerUserId) {
            throw new IllegalStateException("不能收回自己的平台管理员权限");
        }
        roles.deleteByUserIdAndRole(targetUserId, role);
        log.info("收回角色。target={} role={} by={}", targetUserId, role, callerUserId);
    }

    private void requirePlatformAdmin(long callerUserId) {
        if (!roles.existsByUserIdAndRole(callerUserId, Role.PLATFORM_ADMIN)) {
            throw new IllegalStateException("只有平台管理员可以变更角色");
        }
    }

    @Override
    @Transactional("identityTransactionManager")
    public LoginResult loginByPhone(String phone, String code) {
        if (!codes.verify(phone, code)) {
            throw new IllegalArgumentException("验证码错误或已过期");
        }
        Optional<User> existing = users.findByPhone(phone);
        if (existing.isPresent()) {
            User u = existing.get();
            if (u.isBlocked()) {
                throw new IllegalStateException("账号已被封禁");
            }
            grantBootstrapAdminIfListed(phone, u.getId());
            return new LoginResult(u.getId(), jwt.issue(u.getId()), false);
        }
        User created = users.save(new User(phone, generateInviteCode()));
        // 目前没有订阅者,但仍走 outbox:一个类里留两套发事件的机制,
        // 下一个人加订阅时很难判断该用哪套。
        UserRegistered registered = new UserRegistered(
                created.getId(), created.getPhone(), created.getInviteCode(), Instant.now());
        outbox.save(new IdentityOutboxEvent(java.util.UUID.randomUUID().toString(),
                UserRegistered.class.getName(), serialize(registered)));
        grantBootstrapAdminIfListed(phone, created.getId());
        return new LoginResult(created.getId(), jwt.issue(created.getId()), true);
    }

    @Override
    @Transactional(transactionManager = "identityTransactionManager", readOnly = true)
    public Optional<UserView> findVerifiedUser(long userId) {
        return users.findById(userId)
                .map(u -> new UserView(u.getId(), u.getPhone(), u.isVerified(), u.getInviteCode()));
    }

    @Override
    @Transactional("identityTransactionManager")
    public void verifyRealName(long userId, String realName, String idNumber) {
        if (users.existsByIdNumber(idNumber)) {
            throw new IllegalStateException("该身份证已被绑定");
        }
        User u = users.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (u.isVerified()) {
            throw new IllegalStateException("用户已完成实名认证,不可重复认证");
        }
        u.verify(realName, idNumber);
        users.save(u);
        // 事件与实名状态同事务落库,再由中继投递。丢了这条,各域的实名副本里就永远
        // 没有这个人——他报不了名也发不了岗,而且不会有第二次实名事件来补。
        UserVerified verified = new UserVerified(userId, realName, Instant.now());
        outbox.save(new IdentityOutboxEvent(java.util.UUID.randomUUID().toString(),
                UserVerified.class.getName(), serialize(verified)));
    }

    private String generateInviteCode() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
