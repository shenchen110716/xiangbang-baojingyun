package com.xbb.review.internal;

import com.xbb.ops.api.OpsApi;
import com.xbb.review.internal.ReviewTag.Direction;
import com.xbb.review.internal.ReviewTag.TagRule;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 从运营字典取评价词表,交给 {@link ReviewTag} 折算(§5.3.1)。
 *
 * <p>每次都去查字典、不做缓存:运营停用一个标签或调一档权重,下一次评价就该按新的算。
 * 词表统共二十来行,省这点查询换来一个"改了不生效"的坑不划算。
 */
@Component
public class ReviewTagCatalog {

    private final OpsApi ops;

    ReviewTagCatalog(OpsApi ops) {
        this.ops = ops;
    }

    static String dictTypeOf(Direction direction) {
        return direction == Direction.ORG_RATES_WORKER
                ? OpsApi.REVIEW_TAG_ORG_RATES_WORKER
                : OpsApi.REVIEW_TAG_WORKER_RATES_ORG;
    }

    public double score(Direction direction, List<String> tags) {
        return ReviewTag.score(tags, vocabularyOf(direction));
    }

    public boolean isNegative(Direction direction, String tag) {
        TagRule rule = vocabularyOf(direction).get(tag);
        return rule != null && rule.negative();
    }

    /** 某一方向当前可选的标签,给前端出选项用。 */
    public List<String> availableTags(Direction direction) {
        return ops.itemsOf(dictTypeOf(direction)).stream().map(OpsApi.DictItemView::key).toList();
    }

    /**
     * 组装词表。itemsOf 只返回启用的词条——被运营停用的标签查不到,
     * 于是折算时会当成"不属于该方向"直接报错,这正是停用该有的效果。
     */
    private Map<String, TagRule> vocabularyOf(Direction direction) {
        Map<String, Double> weights = severityWeights();
        Map<String, TagRule> vocabulary = new HashMap<>();
        for (OpsApi.DictItemView item : ops.itemsOf(dictTypeOf(direction))) {
            boolean negative = OpsApi.POLARITY_NEGATIVE.equals(item.attribute(OpsApi.ATTR_POLARITY));
            if (!negative) {
                vocabulary.put(item.key(), TagRule.positive(item.key()));
                continue;
            }
            String severity = item.attribute(OpsApi.ATTR_SEVERITY);
            Double weight = weights.get(severity);
            if (weight == null) {
                // 负面标签没配严重度,或配了个词表里没有的档次。这时候不能默认扣 0——
                // 那等于把一条差评悄悄变成好评。宁可让这次评价失败,让运营去把词表配对。
                throw new IllegalStateException(
                        "评价标签 " + item.key() + " 的严重度未配置或不可识别: " + severity);
            }
            vocabulary.put(item.key(), TagRule.negative(item.key(), weight));
        }
        return vocabulary;
    }

    private Map<String, Double> severityWeights() {
        Map<String, Double> weights = new HashMap<>();
        for (OpsApi.DictItemView item : ops.itemsOf(OpsApi.REVIEW_SEVERITY_WEIGHT)) {
            try {
                weights.put(item.key(), Double.parseDouble(item.value()));
            } catch (NumberFormatException e) {
                throw new IllegalStateException(
                        "严重度权重不是数字: " + item.key() + "=" + item.value(), e);
            }
        }
        return weights;
    }
}
