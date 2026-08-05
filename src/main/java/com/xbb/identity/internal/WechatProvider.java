package com.xbb.identity.internal;

/**
 * 微信授权登录通道。
 *
 * <p>拿前端换来的临时 code 去微信换 openid。**这是外部通道**,
 * 所以按铁律 6 有两个要求:实现由 {@code xbb.channel.mode} 显式选择,
 * 凭据没有默认值(漏配时启动失败,而不是悄悄用一个占位符去调真实接口)。
 */
public interface WechatProvider {

    /** @param openId 微信用户在本主体下的唯一标识;unionId 可能为空 */
    record WechatUser(String openId, String unionId, String nickname) { }

    /**
     * 用临时 code 换取用户标识。
     *
     * <p>换不到时**抛异常,不要返回 null** —— 返回 null 会让调用方
     * 在"没换到"和"换到一个空用户"之间做不必要的判断,而后者根本不该存在。
     */
    WechatUser exchange(String code);
}
