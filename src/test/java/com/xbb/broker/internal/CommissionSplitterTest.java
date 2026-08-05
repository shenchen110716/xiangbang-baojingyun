package com.xbb.broker.internal;

import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 六档佣金分账的算术。**纯函数,不起 Spring** —— 分钱的算术要能单独验;
 * 混进仓储调用之后就只能靠搭一整套数据才测得动,跑一次要十几秒,
 * 而这类边界(取整、下限、比例越界)恰恰需要跑很多组。
 *
 * <p>放在 internal 包下是因为被测类是包内可见的 —— 它不该对外暴露。
 */
class CommissionSplitterTest {

    /** 默认口径:主动 60;剩余 40 里 平台 20 / 被动 30 / 服务站 50;逐级 30;下限 100 分。 */
    private static CommissionSplitter.Rates defaults() {
        return new CommissionSplitter.Rates(60, 20, 30, 30, 50, 100);
    }

    private static long tierSum(CommissionSplitter.Split split, CommissionSplitter.Tier tier) {
        return split.shares().stream().filter(s -> s.tier() == tier)
                .mapToLong(CommissionSplitter.Share::amountCents).sum();
    }

    private static List<Long> passiveAmounts(CommissionSplitter.Split split) {
        return split.shares().stream()
                .filter(s -> s.tier() == CommissionSplitter.Tier.PASSIVE)
                .map(CommissionSplitter.Share::amountCents).toList();
    }

    @Test
    void 主动占基数的比例_剩余再按三档分() {
        var split = CommissionSplitter.split(10_000, 1L, 99L, List.of(), defaults());
        // 基数 10000 → 主动 60% = 6000,剩余 4000
        //   平台 4000×20% = 800,服务站 4000×50% = 2000
        assertThat(tierSum(split, CommissionSplitter.Tier.ACTIVE)).isEqualTo(6_000);
        assertThat(split.platformCents()).isEqualTo(800);
        assertThat(tierSum(split, CommissionSplitter.Tier.STATION)).isEqualTo(2_000);
        assertThat(tierSum(split, CommissionSplitter.Tier.PASSIVE)).isZero();  // 没有上级
    }

    @Test
    void 被动沿链逐级递减() {
        // 被动池 = 4000×30% = 1200,每级拿当前剩余的 30%
        //   360(余 840) → 252(余 588) → 176(余 412)
        var split = CommissionSplitter.split(10_000, 1L, null, List.of(2L, 3L, 4L), defaults());
        assertThat(passiveAmounts(split)).containsExactly(360L, 252L, 176L);
        // 越往上越少 —— 这才是"逐级递减"的含义,只对数字容易看不出意图
        assertThat(passiveAmounts(split)).isSortedAccordingTo(Comparator.reverseOrder());
    }

    @Test
    void 被动池低于下限就停止分配() {
        // 下限 500:1200 →360(余840) →252(余588) →176(余412<500 停)
        var rates = new CommissionSplitter.Rates(60, 20, 30, 30, 50, 500);
        var split = CommissionSplitter.split(10_000, 1L, null, List.of(2L, 3L, 4L, 5L, 6L), rates);
        // 不停的话会产生一串一分钱的流水,对账时全是噪音
        assertThat(passiveAmounts(split)).hasSize(3);
    }

    @Test
    void 分账总额永远不超过基数() {
        // 分钱代码最要紧的一条不变式:多分了就是账面上凭空多出钱。
        for (long base : new long[]{1, 7, 99, 100, 333, 10_000, 999_999, 1_000_000_007L}) {
            var split = CommissionSplitter.split(base, 1L, 99L, List.of(2L, 3L, 4L, 5L), defaults());
            assertThat(split.totalCents()).as("基数 %d", base).isLessThanOrEqualTo(base);
        }
    }

    @Test
    void 三档加起来超过100会被拒绝() {
        // 平台+被动+服务站是在同一块"剩余"里分的,加起来超过 100 就是凭空多分。
        var bad = new CommissionSplitter.Rates(60, 40, 40, 30, 40, 100);
        assertThatThrownBy(() -> CommissionSplitter.split(10_000, 1L, 99L, List.of(), bad))
                .hasMessageContaining("超过了可分配的 100%");
    }

    @Test
    void 没有服务站时那一档不分且不转给别人() {
        var split = CommissionSplitter.split(10_000, 1L, null, List.of(), defaults());
        assertThat(tierSum(split, CommissionSplitter.Tier.STATION)).isZero();
        // 少分是安全的,乱分不是
        assertThat(split.totalCents()).isEqualTo(6_000 + 800);
    }

    @Test
    void 基数为零或负时不产生任何分账() {
        assertThat(CommissionSplitter.split(0, 1L, 99L, List.of(2L), defaults()).totalCents()).isZero();
        assertThat(CommissionSplitter.split(-500, 1L, 99L, List.of(2L), defaults()).totalCents()).isZero();
    }

    @Test
    void 极小金额不会因为取整而多分() {
        // 基数 1 分:各档取整后都是 0,总额必须 ≤ 1 而不是被进位
        assertThat(CommissionSplitter.split(1, 1L, 99L, List.of(2L, 3L), defaults()).totalCents())
                .isLessThanOrEqualTo(1);
    }

    @Test
    void 逐级比例非法会被拒绝() {
        assertThatThrownBy(() -> CommissionSplitter.split(10_000, 1L, null, List.of(2L),
                new CommissionSplitter.Rates(60, 20, 30, 0, 50, 100)))
                .hasMessageContaining("逐级");
    }

    // ─────────────── 联合服务站(老系统 M10 §3.4) ───────────────

    @Test
    void 联合分成从归集站自己那份里切_不增加总额() {
        var noJoint = CommissionSplitter.split(100_000, 1L, 9L, List.of(), defaults());
        long stationBefore = tierSum(noJoint, CommissionSplitter.Tier.STATION);

        var withJoint = CommissionSplitter.split(100_000, 1L, 9L, List.of(), defaults(),
                List.of(new CommissionSplitter.Joint(77L, 30)));

        long joint   = tierSum(withJoint, CommissionSplitter.Tier.JOINT);
        long station = tierSum(withJoint, CommissionSplitter.Tier.STATION);

        // 联合方拿走归集站那份的 30%,剩下的还是归集站的
        assertThat(joint).isEqualTo(stationBefore * 30 / 100);
        assertThat(joint + station).isEqualTo(stationBefore);
        // **总额不变。**从基数里另算的话平台就在倒贴钱,而且要等对账才发现
        assertThat(withJoint.totalCents()).isEqualTo(noJoint.totalCents());
    }

    @Test
    void 多个联合方累计不会超过归集站那份() {
        // 三个 40% 各自合法,加起来 120% —— 比例是分别设的,没人会发现它们加起来超了
        var split = CommissionSplitter.split(100_000, 1L, 9L, List.of(), defaults(),
                List.of(new CommissionSplitter.Joint(71L, 40),
                        new CommissionSplitter.Joint(72L, 40),
                        new CommissionSplitter.Joint(73L, 40)));

        long stationBefore = tierSum(
                CommissionSplitter.split(100_000, 1L, 9L, List.of(), defaults()),
                CommissionSplitter.Tier.STATION);
        long jointTotal = split.shares().stream()
                .filter(x -> x.tier() == CommissionSplitter.Tier.JOINT)
                .mapToLong(CommissionSplitter.Share::amountCents).sum();

        assertThat(jointTotal)
                .as("宁可后面的联合方少拿,也不能让总额超过基数")
                .isLessThanOrEqualTo(stationBefore);
        assertThat(split.totalCents()).isLessThanOrEqualTo(100_000);
    }

    @Test
    void 没有归集站时联合不生效() {
        // 没有服务站就没有那一档可切。硬切的话会凭空多出一笔钱
        var split = CommissionSplitter.split(100_000, 1L, null, List.of(), defaults(),
                List.of(new CommissionSplitter.Joint(77L, 30)));
        assertThat(split.shares()).noneMatch(x -> x.tier() == CommissionSplitter.Tier.JOINT);
    }

    @Test
    void 没有联合时和改动前完全一致() {
        // 这条守的是"加了联合别把原来的分账弄坏" —— 绝大多数服务站没有联合
        var before = CommissionSplitter.split(100_000, 1L, 9L, List.of(2L, 3L), defaults());
        var after  = CommissionSplitter.split(100_000, 1L, 9L, List.of(2L, 3L), defaults(), List.of());
        assertThat(after.totalCents()).isEqualTo(before.totalCents());
        assertThat(after.shares()).hasSameSizeAs(before.shares());
        assertThat(tierSum(after, CommissionSplitter.Tier.STATION))
                .isEqualTo(tierSum(before, CommissionSplitter.Tier.STATION));
    }
}
