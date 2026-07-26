package com.xbb.identity.api;

import java.util.Optional;
import java.util.Set;

public interface IdentityApi {

    record LoginResult(long userId, String token, boolean newUser) { }

    record UserView(long id, String phone, boolean verified, String inviteCode) { }

    LoginResult loginByPhone(String phone, String code);

    Optional<UserView> findVerifiedUser(long userId);

    void verifyRealName(long userId, String realName, String idNumber);

    /**
     * 当前角色。**每次授权判断都来查这里,不从 JWT 里读。**
     *
     * <p>把角色写进 token 的话,收回权限要等 token 过期才生效——
     * 而这里的角色能做的是重放资金事件这类操作,不能有这种窗口。
     * 代价是每次判断多一次本域查询,值得。
     */
    Set<Role> rolesOf(long userId);

    boolean hasRole(long userId, Role role);

    /**
     * 授予角色。只有 {@link Role#PLATFORM_ADMIN} 能改角色——授权链要有根,
     * 否则任何拿到运维权限的人都能给自己升权。
     *
     * @throws IllegalStateException 调用者没有 PLATFORM_ADMIN
     */
    void grantRole(long targetUserId, Role role, long callerUserId);

    void revokeRole(long targetUserId, Role role, long callerUserId);
}
