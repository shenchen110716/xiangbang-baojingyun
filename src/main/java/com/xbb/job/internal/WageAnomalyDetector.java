package com.xbb.job.internal;

import com.xbb.job.api.WageAnomaly;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 薪资异常值质疑(主文档 §5.1 三道防线之二,被称为"**真防线**")。
 *
 * <p>"拿同工种市场价 + 该厂历史发单校验,离谱就反问。日薪 2000 的普工岗系统**必须起疑**。"
 *
 * <p>按 §3.3,这条规则属于**岗位域**而不是语音网关:"语音网关只负责声音→文本→结构化候选,
 * 领域校验规则留在各域内"。语音发单会调它,但表单发单同样该受这条规则保护。
 *
 * <p>纯函数,无 Spring 依赖,可单测可回放。
 */
public class WageAnomalyDetector {

    /**
     * 平台绝对区间。这是"同工种市场价"的**占位实现**——真实市场价库需要按工种、
     * 地域、时段维护,是独立的数据工程,当前没有数据源,**不假装有**。
     * 先用一个宽松的绝对区间兜底,至少能挡住"日薪 2 元"和"日薪 2 万"这种量级错误。
     */
    public static final long ABSOLUTE_MIN_CENTS = 5_000;      // 50 元
    public static final long ABSOLUTE_MAX_CENTS = 500_000;    // 5000 元

    /** 偏离该厂历史中位数超过这个倍数就质疑。3 倍是留足正常浮动(加班/旺季)后的量级差。 */
    static final double DEVIATION_MULTIPLE = 3.0;

    /**
     * @param historicalWageCents 该组织的历史发单薪资。为空表示新厂,只用绝对区间判断——
     *                            "没历史"不能成为放行一切的理由
     * @return 有疑点则返回质疑理由;正常则 empty。**只质疑不拦截**——§5.1 的设计是"反问",
     *         用户确认后仍可发布
     */
    public Optional<WageAnomaly> detect(long proposedWageCents, List<Long> historicalWageCents) {
        List<String> reasons = new ArrayList<>();

        if (proposedWageCents < ABSOLUTE_MIN_CENTS) {
            reasons.add("低于平台最低参考 %d 元".formatted(ABSOLUTE_MIN_CENTS / 100));
        } else if (proposedWageCents > ABSOLUTE_MAX_CENTS) {
            reasons.add("高于平台最高参考 %d 元".formatted(ABSOLUTE_MAX_CENTS / 100));
        }

        median(historicalWageCents).ifPresent(median -> {
            if (proposedWageCents > median * DEVIATION_MULTIPLE) {
                reasons.add("比贵司历史发单(约 %d 元)高出 %.1f 倍"
                        .formatted(median / 100, (double) proposedWageCents / median));
            } else if (proposedWageCents * DEVIATION_MULTIPLE < median) {
                reasons.add("比贵司历史发单(约 %d 元)低出 %.1f 倍"
                        .formatted(median / 100, (double) median / proposedWageCents));
            }
        });

        return reasons.isEmpty() ? Optional.empty() : Optional.of(new WageAnomaly(String.join(";", reasons)));
    }

    private static Optional<Long> median(List<Long> values) {
        if (values == null || values.isEmpty()) return Optional.empty();
        List<Long> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        // 用中位数而不是平均数:一次录错的离谱历史单不该把参考基准整体带偏
        long m = n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
        return m == 0 ? Optional.empty() : Optional.of(m);
    }
}
