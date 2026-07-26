package com.xbb.ops.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 运营域(§4.2)。当前做**字典**与**协议模板**——它们是各域受控词表/文案的运营侧抓手。
 *
 * <p>RBAC 与审核入口暂不重复造:各域已有自己的鉴权(法人代表校验、JWT 身份),
 * 再叠一层通用 RBAC 需要先统一权限模型,是独立的一块。
 */
public interface OpsApi {

    /** 技能标签受控词表(§5.2.1)。画像域校验自述标签时查它。 */
    String SKILL_TAG = "SKILL_TAG";

    /**
     * 评价标签(§5.3.1)。**按方向分成两个词表**,而不是塞进一个再拿属性区分方向:
     * 工厂评工人和工人评工厂是两套互不通用的词,分开之后
     * {@link #itemsOf(String)} 一次就能取出某一侧该显示的全部标签。
     */
    String REVIEW_TAG_ORG_RATES_WORKER = "REVIEW_TAG_ORG_RATES_WORKER";
    String REVIEW_TAG_WORKER_RATES_ORG = "REVIEW_TAG_WORKER_RATES_ORG";

    /** 严重度→扣分权重(§5.3.1 轻 0.5 / 中 1.0 / 重 2.5)。运营可整体重新调校。 */
    String REVIEW_SEVERITY_WEIGHT = "REVIEW_SEVERITY_WEIGHT";

    /** 评价标签的属性名:极性(POSITIVE/NEGATIVE)与严重度(LIGHT/MEDIUM/HEAVY)。 */
    String ATTR_POLARITY = "polarity";
    String ATTR_SEVERITY = "severity";
    String POLARITY_POSITIVE = "POSITIVE";
    String POLARITY_NEGATIVE = "NEGATIVE";

    /** 灵活用工劳务协议模板。 */
    String LABOR_AGREEMENT = "LABOR_AGREEMENT";

    record DictItemView(long id, String dictType, String key, String value, int sortOrder,
                        boolean enabled, Map<String, String> attributes) {

        /** 取属性,没有则返回 null——调用方通常要按"有没有这个属性"分支。 */
        public String attribute(String name) {
            return attributes == null ? null : attributes.get(name);
        }
    }

    record AgreementTemplateView(long id, String templateKey, int version, String body, boolean active) { }

    long addItem(String dictType, String key, String value, int sortOrder);

    /** 带结构化属性的词条(评价标签用)。 */
    long addItem(String dictType, String key, String value, int sortOrder, Map<String, String> attributes);

    /** 改词条属性(例如把某个标签从"中"重判为"重")。 */
    void updateAttributes(long itemId, Map<String, String> attributes);

    /** 改词条的值(严重度权重这类"值就是数字"的词表靠它调校)。 */
    void updateValue(long itemId, String value);

    void disableItem(long itemId);

    void enableItem(long itemId);

    /** 只返回启用的,按排序权重升序。 */
    List<DictItemView> itemsOf(String dictType);

    Optional<DictItemView> findItem(String dictType, String key);

    /**
     * 发布模板新版本并立即生效,上一版自动下架。返回新版本号。
     *
     * <p>只增不改:已生效版本的正文永不修改,否则签过的协议就追溯不到当时的文本了。
     */
    int publishTemplate(String templateKey, String body);

    Optional<AgreementTemplateView> activeTemplate(String templateKey);

    /** 按版本号回溯——纠纷举证时用,拿协议上记的版本号翻出当时的模板。 */
    Optional<AgreementTemplateView> templateVersion(String templateKey, int version);
}
