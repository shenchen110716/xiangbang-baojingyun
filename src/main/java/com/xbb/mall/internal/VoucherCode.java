package com.xbb.mall.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 核销码生成(§6.3.6 R5:"核销码**含防伪签名**,核销后立即失效,
 * 同码二次出示直接拒绝","防止截图分享、重复蹭入场")。
 *
 * <p>签名让码不可伪造:光知道订单号猜不出码。一次性由订单状态机保证。
 */
@org.springframework.stereotype.Component
class VoucherCode {

    /**
     * 签名密钥。**不带默认值**——漏配时启动失败,而不是用一个仓库里公开的字符串签名。
     *
     * <p>这里原来硬编码成 "xbb-voucher-signing-key",注释还写着"生产应从密钥管理读取,
     * 这里是占位"。占位符留在主源码里就不是占位符了:核销码 = 订单号 + 签名前 12 位,
     * 密钥公开等于**任何人都能离线算出任意订单的核销码**,不用支付就能入场。
     * 这条违反本项目自己的铁律 6,是全方位测试时由静态扫描抓出来的。
     */
    private final String secret;

    VoucherCode(@org.springframework.beans.factory.annotation.Value("${xbb.mall.voucher-secret}") String secret) {
        this.secret = secret;
    }

    String generate(long orderId, long productId, long buyerUserId) {
        String payload = orderId + ":" + productId + ":" + buyerUserId;
        return "V" + orderId + "-" + sign(payload).substring(0, 12);
    }

    boolean verify(String code, long orderId, long productId, long buyerUserId) {
        return generate(orderId, productId, buyerUserId).equals(code);
    }

    private String sign(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest((secret + "|" + payload).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 必须支持 SHA-256", e);
        }
    }
}
