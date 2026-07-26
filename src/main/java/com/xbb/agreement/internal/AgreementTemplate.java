package com.xbb.agreement.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 协议内容 = 模板 + 变量(主文档 §6.2"协议内容由模板 + 变量生成,
 * 模板由运营维护、法务审定")。
 *
 * <p>模板正文已经搬进运营域(ops.agreement_template,带版本与生效标记),
 * 这里只剩渲染与存证哈希——都是纯函数,不起 Spring。
 *
 * <p>占位符写成 <code>{名字}</code>:模板是给运营和法务改的,位置参数(%d)
 * 一旦顺序错了,轻则渲染出张冠李戴的协议,重则直接抛异常,而且错处一眼看不出来。
 */
public final class AgreementTemplate {

    private AgreementTemplate() { }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

    /**
     * 渲染协议正文。同样的模板加同样的变量必须渲染出同样的文本——存证要可复现。
     *
     * @throws IllegalStateException 模板里有变量表没提供的占位符。不能留着 {xxx} 原样
     *                               输出:那会变成一份带占位符的协议被签下去。
     */
    public static String render(String body, long applicationId, long jobId, long workerUserId,
                                 long orgId, long wageCents) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("applicationId", String.valueOf(applicationId));
        variables.put("jobId", String.valueOf(jobId));
        variables.put("workerUserId", String.valueOf(workerUserId));
        variables.put("orgId", String.valueOf(orgId));
        variables.put("wage", "%d.%02d".formatted(wageCents / 100, wageCents % 100));

        Matcher matcher = PLACEHOLDER.matcher(body);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = variables.get(name);
            if (value == null) {
                throw new IllegalStateException("协议模板含未知占位符: {" + name + "}");
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
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
