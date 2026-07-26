package com.xbb.voice.internal;

import java.util.Optional;

/**
 * 回读文本组装(主文档 §5.1 防线①)。纯函数。
 *
 * <p>"TTS 回读:'二十个普工,两百块一天,包吃住,明天白班,对吗?'"
 * 数字一律走 {@link SpokenNumber} 口语化——这是防线的关键,不是格式偏好。
 */
public final class ReadbackComposer {

    private ReadbackComposer() { }

    public record JobDraft(String title, int headcount, long wageCents, String extra) { }

    /**
     * @param anomalyReason 岗位域给出的薪资质疑(§5.1 防线②)。有则把反问拼进回读——
     *                      "日薪 2000 的普工岗系统必须起疑"
     */
    public static String compose(JobDraft draft, Optional<String> anomalyReason) {
        StringBuilder sb = new StringBuilder();
        sb.append(SpokenNumber.of(draft.headcount())).append("个")
          .append(draft.title()).append(",")
          .append(SpokenNumber.money(draft.wageCents())).append("一天");
        if (draft.extra() != null && !draft.extra().isBlank()) {
            sb.append(",").append(draft.extra());
        }
        anomalyReason.ifPresent(reason ->
                sb.append("。不过这个价格").append(reason).append(",确定吗?"));
        if (anomalyReason.isEmpty()) {
            sb.append(",对吗?");
        }
        return sb.toString();
    }
}
