package com.xbb.voice.internal;

/**
 * 阿拉伯数字转中文口语(主文档 §5.1 三道防线之一)。
 *
 * <p>"TTS 用**口语化数字**:'**两百块**一天''**二十个**人'。
 * 200/2000 在口语里是'两百'vs'两千',**听觉差异远大于视觉**。"
 *
 * <p>这就是为什么不能直接把 "200" 念成 "二零零"——那样 200 和 2000 听起来
 * 是"二零零"和"二零零零",差异只有一个音节,恰恰是最容易听漏的地方。
 *
 * <p>纯函数,无 Spring 依赖。
 */
public final class SpokenNumber {

    private SpokenNumber() { }

    private static final String[] DIGITS = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九"};

    /**
     * 转成口语。注意"二"与"两"的区别:百/千/万的**位首**用"两"(两百、两千、两万),
     * 十位和个位用"二"(二十、十二)。这是中文口语的真实习惯,念成"二百"虽然能懂,
     * 但不如"两百"自然,而自然度直接影响用户听清的概率。
     */
    public static String of(long value) {
        if (value == 0) return "零";
        if (value < 0) return "负" + of(-value);
        if (value >= 100_000_000L) return fallback(value);   // 亿以上不做口语化,回落到逐位读

        StringBuilder sb = new StringBuilder();
        long remaining = value;

        long yi = remaining / 10_000;
        if (yi > 0) {
            // 万位上的 2 同样念"两"(两万),不是"二万"
            sb.append(yi < 10 ? unitDigit(yi) : section(yi, true)).append("万");
            remaining %= 10_000;
            // "两万零五百" 里的零:高位段结束后若剩余部分不足千位,要补"零"
            if (remaining > 0 && remaining < 1000) sb.append("零");
        }
        if (remaining > 0) sb.append(section(remaining, sb.isEmpty()));
        return sb.toString();
    }

    /** 处理 1..9999 的一段。 */
    private static String section(long value, boolean isLeading) {
        StringBuilder sb = new StringBuilder();
        long qian = value / 1000;
        long bai = (value % 1000) / 100;
        long shi = (value % 100) / 10;
        long ge = value % 10;
        boolean needZero = false;

        if (qian > 0) {
            sb.append(unitDigit(qian)).append("千");
        }
        if (bai > 0) {
            if (qian > 0 && sb.length() > 0 && value % 1000 < 100) needZero = true;
            sb.append(unitDigit(bai)).append("百");
        } else if (qian > 0 && (shi > 0 || ge > 0)) {
            sb.append("零");
        }
        if (shi > 0) {
            // "一十" 在口语里说"十"(十二、十五),但前面有更高位时说"一十"(一百一十)
            if (shi == 1 && sb.isEmpty() && isLeading) sb.append("十");
            else sb.append(DIGITS[(int) shi]).append("十");
        } else if (ge > 0 && (bai > 0 || (qian > 0 && bai == 0))) {
            if (!sb.toString().endsWith("零")) sb.append("零");
        }
        if (ge > 0) sb.append(DIGITS[(int) ge]);
        return sb.toString();
    }

    /** 百/千/万的位首:2 念"两"不念"二"。 */
    private static String unitDigit(long d) {
        return d == 2 ? "两" : DIGITS[(int) d];
    }

    private static String fallback(long value) {
        StringBuilder sb = new StringBuilder();
        for (char c : String.valueOf(value).toCharArray()) sb.append(DIGITS[c - '0']);
        return sb.toString();
    }

    /** 金额(分)转口语,如 20000 分 → "两百块"。 */
    public static String money(long cents) {
        long yuan = cents / 100;
        long fen = cents % 100;
        if (fen == 0) return of(yuan) + "块";
        return of(yuan) + "块" + of(fen / 10 * 10 + fen % 10) + "分";
    }
}
