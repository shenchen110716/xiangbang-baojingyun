package com.xbb.identity.internal;

import com.xbb.identity.api.IdentityApi;
import com.xbb.identity.api.UserRegistered;
import com.xbb.identity.api.UserVerified;
import com.xbb.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;

@Service
class IdentityService implements IdentityApi {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private final SecureRandom random = new SecureRandom();

    private final UserRepository users;
    private final VerificationCodeService codes;
    private final JwtService jwt;
    private final IdentityOutboxRepository outbox;
    private final ObjectMapper json;

    IdentityService(UserRepository users, VerificationCodeService codes,
                    JwtService jwt, IdentityOutboxRepository outbox, ObjectMapper json) {
        this.users = users;
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
            return new LoginResult(u.getId(), jwt.issue(u.getId()), false);
        }
        User created = users.save(new User(phone, generateInviteCode()));
        // 目前没有订阅者,但仍走 outbox:一个类里留两套发事件的机制,
        // 下一个人加订阅时很难判断该用哪套。
        UserRegistered registered = new UserRegistered(
                created.getId(), created.getPhone(), created.getInviteCode(), Instant.now());
        outbox.save(new IdentityOutboxEvent(java.util.UUID.randomUUID().toString(),
                UserRegistered.class.getName(), serialize(registered)));
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
