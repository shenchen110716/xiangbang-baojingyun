package com.xbb.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
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

    /** 构造函数上第 n 个参数的 {@code @Value} 表达式。 */
    private static String valueOf(int index) throws Exception {
        var ctor = Class.forName("com.xbb.identity.internal.RealWechatProvider")
                .getDeclaredConstructors()[0];
        for (var a : ctor.getParameterAnnotations()[index]) {
            if (a instanceof org.springframework.beans.factory.annotation.Value v) {
                return v.value();
            }
        }
        throw new AssertionError("第 " + index + " 个参数上没有 @Value");
    }

    @Test
    void 凭据的占位符必须带默认值否则那句提示永远打不出来() throws Exception {
        // 上面那两个测试证明的是"构造器会拦下漏配"。**但它们绕过了 Spring**,
        // 而占位符没有默认值时,漏配在解析阶段就崩成
        // `Could not resolve placeholder 'xbb.wechat.app-id'` ——
        // 构造器压根不会被调用,那句"请设置 XBB_WECHAT_APP_ID"永远打不出来。
        //
        // 这正是"守卫在被守对象损坏时仍然通过"的形状:把默认值删掉,
        // 上面两个测试**依然全绿**,而线上换来的是一句没人看得懂的报错。
        assertThat(valueOf(0)).as("app-id 的占位符要带 `:` 默认值").isEqualTo("${xbb.wechat.app-id:}");
        assertThat(valueOf(1)).isEqualTo("${xbb.wechat.app-secret:}");
    }

    @Test
    void 端点默认值必须是小程序那条() throws Exception {
        // 老板 2026-08-06 确认用的是**小程序**。默认值就按小程序来,
        // 部署时不用再配端点 —— 少一个必填项就少一处能漏配的地方。
        //
        // 上面 build() 是把端点**当参数传进去的**,所以它证明不了默认值是什么。
        // 默认值被人改成公众号那条的话,前面每个测试都照样绿,
        // 而线上会拿 js_code 去调 oauth2/access_token,每次登录都失败
        assertThat(valueOf(2)).contains("sns/jscode2session");
        assertThat(valueOf(3)).isEqualTo("${xbb.wechat.code-param:js_code}");
    }
}
