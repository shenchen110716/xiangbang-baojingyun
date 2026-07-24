package com.xbb.identity.internal;

import com.xbb.identity.api.IdentityApi;
import com.xbb.identity.api.UserRegistered;
import com.xbb.identity.api.UserVerified;
import com.xbb.security.JwtService;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ApplicationEventPublisher events;

    IdentityService(UserRepository users, VerificationCodeService codes,
                    JwtService jwt, ApplicationEventPublisher events) {
        this.users = users;
        this.codes = codes;
        this.jwt = jwt;
        this.events = events;
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
        events.publishEvent(new UserRegistered(
                created.getId(), created.getPhone(), created.getInviteCode(), Instant.now()));
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
        events.publishEvent(new UserVerified(userId, realName, Instant.now()));
    }

    private String generateInviteCode() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
