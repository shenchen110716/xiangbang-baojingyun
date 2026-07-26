package com.xbb.review.internal;

import java.util.List;
import java.util.Map;

/**
 * 评价折算规则(主文档 §5.3.1)。
 *
 * <p>用标签不用星级的理由:"蓝领对'4星还是5星'没感觉,对'准时/跑单'有感觉。
 * 让他选标签,系统算分",把评价成本压到 3 秒(§5.3 R3)。
 *
 * <p>**词表本身已经搬去运营字典**(ops.dictionary_item 里的 REVIEW_TAG_* 词表),
 * 这里只剩纯粹的折算算法:给一份词表和一组标签,算出分数。
 * 这么切之后算法仍能脱离 Spring 单测,而运营改词、改权重不用发版。
 * 取词表的活儿在 {@link ReviewTagCatalog}。
 */
public final class ReviewTag {

    private ReviewTag() { }

    /** 谁评谁。两个方向的标签集不可混用。 */
    public enum Direction { ORG_RATES_WORKER, WORKER_RATES_ORG }

    /**
     * 一个标签在折算里的全部信息。
     *
     * <p>正面标签扣 0 分不是敷衍:§5.3.1 明确正面标签不加分也不抵消负面,
     * 它是给对方看的定性信息,不进折算。
     */
    public record TagRule(String tag, boolean negative, double penalty) {

        public static TagRule positive(String tag) { return new TagRule(tag, false, 0.0); }

        public static TagRule negative(String tag, double penalty) { return new TagRule(tag, true, penalty); }
    }

    /**
     * 计入信用分违约惩罚项的标签(§5.3.1)。
     *
     * <p>这几个 key 留在代码里而没随词表一起运营化,是因为**代码要按它们分支**:
     * 跑单进信用分惩罚、拖欠工资触发平台预警、描述不实下调岗位画像置信度。
     * 运营可以改它们的显示文案和严重度,但 key 是代码与词表之间的契约,改 key 就要改代码。
     */
    public static final String MID_QUIT = "中途跑单";

    /** 触发平台预警的标签(§5.3.1)。 */
    public static final String WAGE_ARREARS = "拖欠工资";

    /** 应下调工厂岗位画像置信度的标签(§5.3.1;目前只识别,联动留给后续)。 */
    public static final String MISLEADING = "描述不实";

    public static final double MAX_SCORE = 5.0;
    public static final double MIN_SCORE = 1.0;

    /**
     * 单次评价分 = 5 − Σ(负面标签扣分),下限 1(§5.3.1)。
     *
     * @param vocabulary 该评价方向的词表,key 为标签
     * @throws IllegalArgumentException 标签不在该方向的词表内(工厂不能用"拖欠工资"评工人)
     */
    public static double score(List<String> tags, Map<String, TagRule> vocabulary) {
        double penalty = 0.0;
        for (String tag : tags) {
            TagRule rule = vocabulary.get(tag);
            if (rule == null) {
                throw new IllegalArgumentException("标签不属于该评价方向: " + tag);
            }
            penalty += rule.penalty();
        }
        return Math.max(MIN_SCORE, MAX_SCORE - penalty);
    }
}
