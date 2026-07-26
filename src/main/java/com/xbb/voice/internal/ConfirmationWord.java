package com.xbb.voice.internal;

import java.util.Set;

/**
 * 确认词判定(主文档 §5.1)。
 *
 * <p>"只接受明确肯定(对/确认/没错/可以);**模糊应答('嗯…''行吧')重新回读,不放行**。"
 *
 * <p>这一条是三道防线里最容易被做错的:把"嗯"当成同意,等于防线不存在——
 * 用户"嗯"了一声很可能只是在思考,或者根本没听清。
 */
public final class ConfirmationWord {

    private ConfirmationWord() { }

    public enum Verdict {
        /** 明确肯定,放行 */
        CONFIRMED,
        /** 明确否定,进入修改 */
        REJECTED,
        /** 模糊,重新回读——既不放行也不当否定 */
        AMBIGUOUS
    }

    private static final Set<String> AFFIRMATIVE =
            Set.of("对", "对的", "确认", "没错", "可以", "是的", "对对对", "好的就这样");

    private static final Set<String> NEGATIVE =
            Set.of("不对", "不是", "改", "重来", "错了", "不行", "取消");

    public static Verdict judge(String utterance) {
        if (utterance == null) return Verdict.AMBIGUOUS;
        String u = utterance.trim().replaceAll("[,。!?、\\s]", "");
        if (u.isEmpty()) return Verdict.AMBIGUOUS;

        if (AFFIRMATIVE.contains(u)) return Verdict.CONFIRMED;
        if (NEGATIVE.contains(u)) return Verdict.REJECTED;

        // 否定优先于肯定:"不对"里含"对",不能被当成肯定
        for (String n : NEGATIVE) {
            if (u.contains(n)) return Verdict.REJECTED;
        }
        // 剩下的一律模糊——包括"嗯""行吧""差不多""应该吧"。
        // 不做"包含肯定词就放行"的宽松匹配:"好像可以吧"含"可以",但显然不是确认。
        return Verdict.AMBIGUOUS;
    }
}
