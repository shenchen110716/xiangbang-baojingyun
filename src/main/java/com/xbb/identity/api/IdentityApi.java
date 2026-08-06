package com.xbb.identity.api;

import java.util.Optional;
import java.util.Set;

public interface IdentityApi {

    record LoginResult(long userId, String token, boolean newUser) { }

    record UserView(long id, String phone, boolean verified, String inviteCode) { }

    LoginResult loginByPhone(String phone, String code);

    /**
     * 微信授权登录。
     *
     * <p><b>openid 没绑过时不会凭空建账号。</b>这是刻意的:
     * 平台的核心动作(报名、收工资、当业务员)都要求实名,而实名要手机号。
     * 让微信独立开户的话,会得到一批**永远走不下去的空账号** ——
     * 而且同一个人后来用手机登录时又是另一个账号,两边的业绩、佣金对不起来。
     *
     * <p>所以未绑定时返回 {@code newUser=true} 且 {@code userId=0},
     * 前端据此引导去"手机登录 + 绑定微信"。
     */
    LoginResult loginByWechat(String code);

    /**
     * 把微信绑到**当前已登录的账号**上。
     *
     * <p>一个账号只能绑一个微信,一个微信也只能绑一个账号 ——
     * 操作员授权是按人给的,一人多微信等于一份授权能被多个身份使用。
     */
    void bindWechat(long userId, String code);

    /** 这个账号绑的微信(没绑返回空)。 */
    java.util.Optional<String> wechatOpenIdOf(long userId);

    /**
     * 按 id 查用户。
     *
     * <p><b>名字有误导:它返回任何用户,不筛已实名的。</b>
     * 实名与否看 {@link UserView#verified()} —— 只判 {@code isEmpty()} 的话,
     * 未实名的人会被当成已实名放过去。
     *
     * <p>2026-08-06 有人(我)照名字理解写错过一次,测试当场抓住。
     * 名字没改是因为它已经在几处被调用,改名要一起动;
     * 这段注释是为了让下一个人在读到名字之前先读到它。
     */
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
