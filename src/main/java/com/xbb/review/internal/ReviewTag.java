package com.xbb.review.internal;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 评价标签体系与折算规则(主文档 §5.3.1)。
 *
 * <p>用标签不用星级的理由:"蓝领对'4星还是5星'没感觉,对'准时/跑单'有感觉。
 * 让他选标签,系统算分",把评价成本压到 3 秒(§5.3 R3)。
 *
 * <p>双向,但**标签集不同**——工厂评工人和工人评工厂关心的完全是两回事。
 */
public final class ReviewTag {

    private ReviewTag() { }

    /** 严重度权重(§5.3.1):轻 0.5 / 中 1.0 / 重 2.5。 */
    public enum Severity {
        LIGHT(0.5), MEDIUM(1.0), HEAVY(2.5);

        private final double weight;

        Severity(double weight) { this.weight = weight; }

        public double weight() { return weight; }
    }

    /** 谁评谁。两个方向的标签集不可混用。 */
    public enum Direction { ORG_RATES_WORKER, WORKER_RATES_ORG }

    /** 工厂评工人:中途跑单是"重",计入信用分违约惩罚项(§5.3.1)。 */
    private static final Set<String> ORG_POSITIVE =
            Set.of("准时到岗", "手脚麻利", "服从管理", "干满工期", "态度好");
    private static final Map<String, Severity> ORG_NEGATIVE = Map.of(
            "迟到早退", Severity.LIGHT,
            "消极怠工", Severity.MEDIUM,
            "不服管理", Severity.MEDIUM,
            "技能不符", Severity.MEDIUM,
            "中途跑单", Severity.HEAVY);

    /** 工人评工厂:拖欠工资是"重"且触发平台预警;描述不实要下调该工厂岗位画像置信度。 */
    private static final Set<String> WORKER_POSITIVE =
            Set.of("结算准时", "如实描述", "管理规范", "食宿达标");
    private static final Map<String, Severity> WORKER_NEGATIVE = Map.of(
            "超时加班", Severity.LIGHT,
            "管理粗暴", Severity.MEDIUM,
            "食宿差", Severity.MEDIUM,
            "描述不实", Severity.MEDIUM,
            "拖欠工资", Severity.HEAVY);

    /** 计入信用分违约惩罚项的标签(§5.3.1 两个特殊标签之一)。 */
    public static final String MID_QUIT = "中途跑单";

    /** 触发平台预警的标签(§5.3.1)。 */
    public static final String WAGE_ARREARS = "拖欠工资";

    /** 应下调工厂岗位画像置信度的标签(§5.3.1;本 Plan 只识别,联动留给后续)。 */
    public static final String MISLEADING = "描述不实";

    public static final double MAX_SCORE = 5.0;
    public static final double MIN_SCORE = 1.0;

    /**
     * 单次评价分 = 5 − Σ(负面标签 × 严重度权重),下限 1(§5.3.1)。
     *
     * @throws IllegalArgumentException 标签不在该方向的标签集内(工厂不能用"拖欠工资"评工人)
     */
    public static double score(Direction direction, List<String> tags) {
        Set<String> positive = direction == Direction.ORG_RATES_WORKER ? ORG_POSITIVE : WORKER_POSITIVE;
        Map<String, Severity> negative = direction == Direction.ORG_RATES_WORKER ? ORG_NEGATIVE : WORKER_NEGATIVE;

        double penalty = 0.0;
        for (String tag : tags) {
            Severity severity = negative.get(tag);
            if (severity != null) {
                penalty += severity.weight();
            } else if (!positive.contains(tag)) {
                throw new IllegalArgumentException("标签不属于该评价方向: " + tag);
            }
        }
        return Math.max(MIN_SCORE, MAX_SCORE - penalty);
    }

    public static boolean isNegative(Direction direction, String tag) {
        return (direction == Direction.ORG_RATES_WORKER ? ORG_NEGATIVE : WORKER_NEGATIVE).containsKey(tag);
    }
}
