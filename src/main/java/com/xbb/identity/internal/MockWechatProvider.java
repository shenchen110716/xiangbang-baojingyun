package com.xbb.identity.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 测试与开发用的微信通道。
 *
 * <p><b>它会"成功"返回一个由 code 推导出的 openid。</b>这正是危险之处:
 * 接上它之后登录流程一路通畅,看不出任何异常,而真实的微信授权根本没发生。
 * 所以由 {@code xbb.channel.mode=mock} 显式选择,并且
 * {@link com.xbb.ChannelModeGuard} 会在启动时吼一声。
 */
@Component
@ConditionalOnProperty(name = "xbb.channel.mode", havingValue = "mock")
class MockWechatProvider implements WechatProvider {

    @Override
    public WechatUser exchange(String code) {
        if (code == null || code.isBlank()) {
            // 真实通道也会拒绝空 code。mock 不该比真实实现更宽松 ——
            // 那样测试里过得去的东西,上线会当场失败
            throw new IllegalArgumentException("微信授权码不能为空");
        }
        return new WechatUser("mock-openid-" + code.trim(), null, "微信用户");
    }
}
