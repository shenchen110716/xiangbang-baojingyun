package com.xbb.ops.api;

import java.math.BigDecimal;
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

    // ─────────────── 平台参数 ───────────────

    record SettingView(String key, String value, String valueType, String category,
                       String label, String description, java.time.Instant updatedAt, Long updatedBy) { }

    record SettingChangeView(long id, String key, String oldValue, String newValue,
                             long changedBy, java.time.Instant changedAt, String reason) { }

    /**
     * 读整数参数。**键用 {@link SettingKeys} 的常量。**
     *
     * <p>{@code fallback} 是代码里编译进去的兜底值,只在参数行不存在时用。
     * 正常情况下所有键都由迁移种下,不会走到兜底 —— 走到了说明有人加了读取却忘了种子,
     * 这一条由 {@code SettingsCoverageTest} 守。
     *
     * <p>为什么不在缺失时抛异常:参数缺失让整个应用起不来,代价比"用回原来的常量"大得多,
     * 而后者的行为和改动前完全一致。风险由守卫测试在 CI 阶段消掉,不留到运行期。
     */
    long settingInt(String key, long fallback);

    BigDecimal settingDecimal(String key, BigDecimal fallback);

    /** 全部参数,按分组排序。平台端「参数设置」页用。 */
    List<SettingView> allSettings();

    /**
     * 改参数。要 {@link com.xbb.identity.api.Role#PLATFORM_OPS},并**强制留痕**。
     *
     * @param reason 改动理由,必填。佣金比例这类东西事后要能解释为什么改
     */
    void updateSetting(String key, String value, String reason, long callerUserId);

    /** 改动记录。key 为 null 时返回最近 50 条(全部键)。 */
    List<SettingChangeView> settingChanges(String key, long callerUserId);

    /**
     * 行政区划。<b>只放确有把握的地区</b> —— 没铺全国 300 多个地级市,
     * 因为错一个码就是那个地区的佣金按别处算,而那是静默的。
     * 没覆盖的地区按省配比例即可(取数从细到粗回退)。
     */
    record RegionView(String code, String name, String parentCode, int level) { }

    /**
     * @param parentCode 传 null 返回省级;传省级码返回它下面的市
     */
    java.util.List<RegionView> listRegions(String parentCode);
}
