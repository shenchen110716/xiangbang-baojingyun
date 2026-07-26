package com.xbb.agreement.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 协议内容 = 模板 + 变量(主文档 §6.2"协议内容由模板 + 变量生成,
 * 模板由运营维护、法务审定")。
 *
 * <p>本 Plan 用硬编码模板:运营可维护模板需要模板管理后台 + 版本化 + 法务审核流,
 * 是明显更大的独立工作(同画像域受控词表的取舍)。真做的时候这个类换成读模板表即可,
 * 渲染与哈希逻辑不用动。
 */
public final class AgreementTemplate {

    private AgreementTemplate() { }

    private static final String TEMPLATE = """
            灵活用工劳务协议

            甲方(用工单位):组织 #%d
            乙方(劳动者):用户 #%d

            一、岗位:岗位 #%d
            二、劳务报酬:每单位 %d.%02d 元,由甲方按平台结算规则支付
            三、双方权利义务依照平台规则及国家相关法律法规执行
            四、本协议经乙方电子签署后生效

            (关联履约单 #%d)
            """;

    /** 渲染协议正文。同样的变量必须渲染出同样的文本——存证要可复现。 */
    public static String render(long applicationId, long jobId, long workerUserId, long orgId, long wageCents) {
        return TEMPLATE.formatted(orgId, workerUserId, jobId, wageCents / 100, wageCents % 100, applicationId);
    }

    /** SHA-256 存证哈希。纠纷举证时用它证明协议正文未被篡改。 */
    public static String hash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 必须支持 SHA-256", e);
        }
    }
}
