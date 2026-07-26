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
final class VoucherCode {

    private VoucherCode() { }

    /** 生产环境应从配置/密钥管理读取,这里是占位。 */
    private static final String SECRET = "xbb-voucher-signing-key";

    static String generate(long orderId, long productId, long buyerUserId) {
        String payload = orderId + ":" + productId + ":" + buyerUserId;
        return "V" + orderId + "-" + sign(payload).substring(0, 12);
    }

    static boolean verify(String code, long orderId, long productId, long buyerUserId) {
        return generate(orderId, productId, buyerUserId).equals(code);
    }

    private static String sign(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest((SECRET + "|" + payload).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 必须支持 SHA-256", e);
        }
    }
}
