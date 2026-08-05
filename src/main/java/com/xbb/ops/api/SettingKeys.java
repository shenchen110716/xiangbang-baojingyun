package com.xbb.ops.api;

import java.util.List;

/**
 * 平台参数的键。**各域一律引用这里的常量,不要写字符串字面量。**
 *
 * <p>理由是可检查性:字面量写错了不会报错,只会静默拿到 fallback ——
 * 于是运营在界面上把佣金从 60% 改成 40%,而代码还在用 60%,
 * 账面上完全看不出异常。集中成常量之后,
 * {@code SettingsCoverageTest} 才能断言"每个被代码读取的键都在种子数据里"。
 */
public final class SettingKeys {

    private SettingKeys() { }

    // ── 经纪人 / 业务员 ──
    public static final String BROKER_DEMOTION_DAYS = "broker.demotion.days";

    // ── 佣金分成(基数 = 浮动佣金 × 考勤系数) ──
    public static final String COMMISSION_ACTIVE_PERCENT       = "broker.commission.active.percent";
    public static final String COMMISSION_PLATFORM_PERCENT     = "broker.commission.platform.percent";
    public static final String COMMISSION_PASSIVE_PERCENT      = "broker.commission.passive.percent";
    public static final String COMMISSION_PASSIVE_STEP_PERCENT = "broker.commission.passive.step.percent";
    public static final String COMMISSION_STATION_PERCENT      = "broker.commission.station.percent";
    public static final String COMMISSION_MIN_PAYOUT_CENTS     = "broker.commission.min.payout.cents";

    // ── 信用分 ──
    public static final String CREDIT_NEW_USER_SCORE     = "credit.new.user.score";
    public static final String CREDIT_HALF_LIFE_DAYS     = "credit.half.life.days";
    public static final String CREDIT_PENALTY_K          = "credit.penalty.k";
    public static final String CREDIT_WEIGHT_FULFILLMENT = "credit.weight.fulfillment";
    public static final String CREDIT_WEIGHT_REVIEW      = "credit.weight.review";
    public static final String CREDIT_WEIGHT_PENALTY     = "credit.weight.penalty";
    public static final String CREDIT_RECENT_DAYS        = "credit.recent.days";

    // ── 薪资合理性(只质疑不拦截) ──
    public static final String WAGE_MIN_CENTS          = "wage.min.cents";
    public static final String WAGE_MAX_CENTS          = "wage.max.cents";
    public static final String WAGE_DEVIATION_MULTIPLE = "wage.deviation.multiple";

    // ── 保证金 ──
    public static final String DEPOSIT_FULL_RATE = "deposit.full.rate";

    // ── 匹配 ──
    public static final String MATCHING_DISTANCE_DECAY_KM = "matching.distance.decay.km";
    public static final String MATCHING_EPSILON           = "matching.epsilon";

    // ── 语音发单 ──
    public static final String VOICE_MIN_CONFIDENCE = "voice.min.confidence";

    // ── 业务员自动升级 ──
    /** 凑满几单成交自动升级。**0 表示对方报名即升级**,不等成交。岗位与商品合并计数。 */
    public static final String BROKER_UPGRADE_DEAL_THRESHOLD = "broker.upgrade.deal.threshold";
    /** 继承不到服务站时归入的默认站。0 表示暂不归站。 */
    public static final String BROKER_DEFAULT_STATION_ORG_ID = "broker.default.station.org.id";

    // ── 借支(老系统 M8「借押保」) ──
    /** 单人未还借支上限。**连同已欠的一起算**,否则借十次小额就绕过了上限。 */
    public static final String ADVANCE_MAX_OUTSTANDING_CENTS = "advance.max.outstanding.cents";

    /** 全部键。守卫测试拿它和数据库里的种子逐一对照。 */
    public static final List<String> ALL = List.of(
            BROKER_DEMOTION_DAYS,
            COMMISSION_ACTIVE_PERCENT, COMMISSION_PLATFORM_PERCENT, COMMISSION_PASSIVE_PERCENT,
            COMMISSION_PASSIVE_STEP_PERCENT, COMMISSION_STATION_PERCENT, COMMISSION_MIN_PAYOUT_CENTS,
            CREDIT_NEW_USER_SCORE, CREDIT_HALF_LIFE_DAYS, CREDIT_PENALTY_K,
            CREDIT_WEIGHT_FULFILLMENT, CREDIT_WEIGHT_REVIEW, CREDIT_WEIGHT_PENALTY, CREDIT_RECENT_DAYS,
            WAGE_MIN_CENTS, WAGE_MAX_CENTS, WAGE_DEVIATION_MULTIPLE,
            DEPOSIT_FULL_RATE,
            ADVANCE_MAX_OUTSTANDING_CENTS,
            BROKER_UPGRADE_DEAL_THRESHOLD, BROKER_DEFAULT_STATION_ORG_ID,
            MATCHING_DISTANCE_DECAY_KM, MATCHING_EPSILON,
            VOICE_MIN_CONFIDENCE);
}
