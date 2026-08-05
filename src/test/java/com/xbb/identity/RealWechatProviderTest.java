package com.xbb.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实微信通道的构造期校验。**不起 Spring,不联网** ——
 * 这里要守的是"漏配凭据时能不能启动",而那在构造函数里就决定了。
 *
 * <p>为什么单独守这一条:{@code @Value} 的占位符在**值是空字符串**时不会报错,
 * 应用会带着一个必然失败的通道正常启动 —— 然后每一次微信登录都失败,
 * 而启动日志里干干净净(铁律 6)。
 */
class RealWechatProviderTest {

    private static Object build(String appId, String appSecret) throws Exception {
        var ctor = Class.forName("com.xbb.identity.internal.RealWechatProvider")
                .getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        try {
            return ctor.newInstance(appId, appSecret,
                    "https://api.weixin.qq.com/sns/jscode2session", "js_code", new ObjectMapper());
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    @Test
    void 凭据为空时启动失败而不是带病运行() {
        assertThatThrownBy(() -> build("", "secret")).hasMessageContaining("未配置");
        assertThatThrownBy(() -> build("wxappid", "  ")).hasMessageContaining("未配置");
        assertThatThrownBy(() -> build(null, "secret")).hasMessageContaining("未配置");
    }

    @Test
    void 凭据齐全时能构造出来() {
        assertThatCode(() -> build("wxa28c8d9499eb70e6", "some-secret"))
                .doesNotThrowAnyException();
    }
}
