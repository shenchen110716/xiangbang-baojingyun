package com.xbb.settlement.internal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 计薪算术。纯函数,不起 Spring —— 跑一次几十毫秒,所以可以密集覆盖边界。
 *
 * <p>放在 internal 包下:被测类是包内可见的,它不该对外暴露。
 */
class WageCalculatorTest {

    private static WageCalculator.Plan hourly(long basic, long floating, long fixed,
                                              WageCalculator.Factor... factors) {
        return new WageCalculator.Plan(WageCalculator.PayType.HOURLY, basic, floating, fixed,
                List.of(factors));
    }

    @Test
    void 按小时计薪_先乘后除不抹掉不满一小时的部分() {
        // 时薪 2500 分(25 元),干了 90 分钟 = 1.5 小时 → 3750 分
        var r = WageCalculator.compute(hourly(2_500, 0, 0), 90, 1);
        assertThat(r.basicCents()).isEqualTo(3_750);

        // 若先除后乘(90/60=1 小时),会算成 2500 —— 一个月下来能差出一天工资
        assertThat(r.basicCents()).isNotEqualTo(2_500);
    }

    @Test
    void 浮动工资单独算出来_它是佣金基数() {
        // 老系统拿 floatSalary 算佣金,不是拿应发总额
        var r = WageCalculator.compute(hourly(2_000, 500, 10_000), 120, 1);
        assertThat(r.basicCents()).isEqualTo(4_000);
        assertThat(r.floatCents()).isEqualTo(1_000);
        assertThat(r.commissionBaseCents())
                .as("佣金基数应当是浮动工资,不是应发总额")
                .isEqualTo(1_000)
                .isNotEqualTo(r.grossCents());
    }

    @Test
    void 固定工资不随工时变() {
        var a = WageCalculator.compute(hourly(0, 0, 50_000), 60, 1);
        var b = WageCalculator.compute(hourly(0, 0, 50_000), 600, 10);
        assertThat(a.fixedCents()).isEqualTo(b.fixedCents()).isEqualTo(50_000);
    }

    @Test
    void 奖励加钱_扣款与罚款减钱() {
        var r = WageCalculator.compute(hourly(2_000, 0, 0, 60,
                new WageCalculator.Factor(WageCalculator.FactorType.BONUS, "全勤奖", 10_000),
                new WageCalculator.Factor(WageCalculator.FactorType.DEDUCTION, "宿舍水电", 3_000),
                new WageCalculator.Factor(WageCalculator.FactorType.PENALTY, "迟到罚款", 2_000)),
                60, 1);
        assertThat(r.additionsCents()).isEqualTo(10_000);
        assertThat(r.deductionsCents()).isEqualTo(5_000);
        assertThat(r.grossCents()).isEqualTo(2_000 + 10_000 - 5_000);
    }

    @Test
    void 扣款超过应得时不产生负工资() {
        // 负工资意味着"这个月工人倒欠工厂钱",不该由计薪静默产生。
        // 欠款要走借支/还款那条路,不是把工资算成负数。
        var r = WageCalculator.compute(hourly(1_000, 0, 0, 60,
                new WageCalculator.Factor(WageCalculator.FactorType.PENALTY, "巨额罚款", 999_999)),
                60, 1);
        assertThat(r.grossCents()).isZero();
    }

    @Test
    void 按月计薪_零出勤不发() {
        var plan = new WageCalculator.Plan(WageCalculator.PayType.MONTHLY,
                500_000, 100_000, 0, List.of());
        assertThat(WageCalculator.compute(plan, 0, 0).grossCents())
                .as("整月没来不该拿满月工资").isZero();
        assertThat(WageCalculator.compute(plan, 60, 1).grossCents()).isEqualTo(600_000);
    }

    @Test
    void 按天计薪_看出勤天数不看时长() {
        var plan = new WageCalculator.Plan(WageCalculator.PayType.DAILY, 20_000, 0, 0, List.of());
        // 三天,每天时长不同,但按天计薪只看天数
        assertThat(WageCalculator.compute(plan, 500, 3).basicCents()).isEqualTo(60_000);
        assertThat(WageCalculator.compute(plan, 1_400, 3).basicCents()).isEqualTo(60_000);
    }

    @Test
    void 零工时不产生工资() {
        assertThat(WageCalculator.compute(hourly(2_500, 500, 0), 0, 0).grossCents()).isZero();
    }

    @Test
    void 调整项金额为负会被拒绝() {
        // 金额存正数、方向由类型决定。让两种写法并存,迟早有人加错符号
        assertThatThrownBy(() -> WageCalculator.compute(hourly(2_000, 0, 0, 60,
                new WageCalculator.Factor(WageCalculator.FactorType.DEDUCTION, "负数扣款", -100)),
                60, 1))
                .hasMessageContaining("必须为正数");
    }

    @Test
    void 明细能解释总额() {
        var r = WageCalculator.compute(hourly(2_000, 500, 10_000, 60,
                new WageCalculator.Factor(WageCalculator.FactorType.BONUS, "全勤奖", 5_000),
                new WageCalculator.Factor(WageCalculator.FactorType.DEDUCTION, "水电", 1_000)),
                60, 1);
        // 明细逐行加起来必须等于应发 —— 对不上就是"算得出但解释不了"
        long sum = r.lines().stream().mapToLong(WageCalculator.Line::amountCents).sum();
        assertThat(sum).isEqualTo(r.grossCents());
    }

    @Test
    void 按件计薪明确报错而不是静默算成零() {
        var plan = new WageCalculator.Plan(WageCalculator.PayType.PIECE, 100, 0, 0, List.of());
        // 静默返回 0 会让"按件的岗位一分钱不发"变成无声故障
        assertThatThrownBy(() -> WageCalculator.compute(plan, 480, 1))
                .hasMessageContaining("按件");
    }

    @Test
    void 工时或天数为负被拒绝() {
        assertThatThrownBy(() -> WageCalculator.compute(hourly(2_000, 0, 0), -1, 1))
                .hasMessageContaining("不能为负");
    }

    // 便于在参数里插调整项的重载
    private static WageCalculator.Plan hourly(long basic, long floating, long fixed, int ignored,
                                              WageCalculator.Factor... factors) {
        return hourly(basic, floating, fixed, factors);
    }
}
