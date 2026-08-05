package com.xbb.identity.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 真实的微信授权通道。
 *
 * <p>默认走**小程序**那条 {@code sns/jscode2session}(项目里有小程序,操作员多半在手机上用)。
 * 公众号网页授权只需把 {@code xbb.wechat.endpoint} 改成
 * {@code https://api.weixin.qq.com/sns/oauth2/access_token},参数名由 {@code xbb.wechat.code-param} 控制
 * (小程序是 {@code js_code},公众号是 {@code code})—— 两条路只差这两处。
 *
 * <p><b>凭据没有默认值。</b>漏配 appId/appSecret 时应用**启动失败**,
 * 而不是拿着空串去调微信、拿到一个看不懂的错误(铁律 6)。
 */
@Component
@ConditionalOnProperty(name = "xbb.channel.mode", havingValue = "real")
class RealWechatProvider implements WechatProvider {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RealWechatProvider.class);

    private final String appId;
    private final String appSecret;
    private final String endpoint;
    private final String codeParam;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    // 两个键都给空串兜底,**不是为了容忍漏配,恰恰相反**:
    // 不给默认值时,漏配会在占位符解析阶段就崩成
    // `Could not resolve placeholder 'xbb.wechat.app-id'` ——
    // 下面那句写清了该设哪两个环境变量的提示**永远轮不到执行**。
    // 给空串,让检查落到构造器里,报出人看得懂的话
    RealWechatProvider(@Value("${xbb.wechat.app-id:}") String appId,
                       @Value("${xbb.wechat.app-secret:}") String appSecret,
                       @Value("${xbb.wechat.endpoint:https://api.weixin.qq.com/sns/jscode2session}")
                       String endpoint,
                       @Value("${xbb.wechat.code-param:js_code}") String codeParam,
                       ObjectMapper json) {
        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()) {
            // 空串也算漏配。**这一条不能省** —— @Value 有值但值是空字符串时,
            // 上面的占位符不会报错,应用会带着一个必然失败的通道正常启动
            throw new IllegalStateException(
                    "微信 app-id / app-secret 未配置。请设置 XBB_WECHAT_APP_ID 与 XBB_WECHAT_APP_SECRET");
        }
        this.appId = appId;
        this.appSecret = appSecret;
        this.endpoint = endpoint;
        this.codeParam = codeParam;
        this.json = json;
        log.info("微信通道已启用:appId={} endpoint={}", mask(appId), endpoint);
    }

    /** 日志里只留前后各四位。**appId 不是密钥,但完整值仍不该随日志散出去。** */
    private static String mask(String s) {
        return s.length() <= 8 ? "****" : s.substring(0, 4) + "****" + s.substring(s.length() - 4);
    }

    @Override
    public WechatUser exchange(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("微信授权码不能为空");
        }
        String url = "%s?appid=%s&secret=%s&%s=%s&grant_type=authorization_code".formatted(
                endpoint, enc(appId), enc(appSecret), codeParam, enc(code.trim()));
        try {
            HttpResponse<String> res = http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonNode body = json.readTree(res.body());
            // **微信用 HTTP 200 + errcode 表示失败**,不看 errcode 的话
            // 会把一个错误响应当成成功,然后拿到 openid=null 往下走
            if (body.hasNonNull("errcode") && body.get("errcode").asInt() != 0) {
                throw new IllegalStateException("微信授权失败:errcode=%d errmsg=%s".formatted(
                        body.get("errcode").asInt(), body.path("errmsg").asText("")));
            }
            String openId = body.path("openid").asText(null);
            if (openId == null || openId.isBlank()) {
                throw new IllegalStateException("微信返回里没有 openid,响应体:" + res.body());
            }
            return new WechatUser(openId,
                    body.path("unionid").asText(null),
                    null);   // 这两个接口都不返回昵称,要另外调用户信息接口
        } catch (java.io.IOException e) {
            throw new IllegalStateException("调用微信接口失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("调用微信接口被中断", e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
