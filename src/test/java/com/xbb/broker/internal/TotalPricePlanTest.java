package com.xbb.broker.internal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 总价模式的三段拆分,和地区的层级回退。
 *
 * <p><b>不起 Spring。</b>钱的算法要能被单独按住看 ——
 * 混在带事务的服务里,每验一个边界都要起一次上下文,于是边界就没人验了。
 */
class TotalPricePlanTest {

    @Test
    void 三段相加正好等于佣金总额() {
        // 总价 1000 元,佣金 10% = 100 元,派遣留存 30% = 30 元,服务站池 70 元
        var p = TotalPricePlan.of(100_000, 10, 30);
        assertThat(p.commissionCents()).isEqualTo(10_000);
        assertThat(p.dispatchRetainCents()).isEqualTo(3_000);
        assertThat(p.stationPoolCents()).isEqualTo(7_000);
        assertThat(p.dispatchRetainCents() + p.stationPoolCents()).isEqualTo(p.commissionCents());
    }

    @Test
    void 除不尽时也不会凭空多一分或少一分() {
        // 佣金 333 分,留存 33% —— 两段各自算百分比的话会对不上
        var p = TotalPricePlan.of(3_333, 10, 33);
        assertThat(p.commissionCents()).isEqualTo(333);
        assertThat(p.dispatchRetainCents()).isEqualTo(109);
        // **服务站那笔是减出来的,不是再算一次百分比。**
        // 各算各的会得到 109 + 223 = 332,少一分,而那一分谁也说不清去哪了
        assertThat(p.stationPoolCents()).isEqualTo(224);
        assertThat(p.dispatchRetainCents() + p.stationPoolCents()).isEqualTo(p.commissionCents());
    }

    @Test
    void 没有派遣公司时留存为零_整笔进服务站池() {
        var p = TotalPricePlan.of(100_000, 10, 0);
        assertThat(p.dispatchRetainCents()).isZero();
        assertThat(p.stationPoolCents()).isEqualTo(p.commissionCents());
    }

    @Test
    void 比例越界当场报错() {
        // 放过去的话会分出负数或超过总价的佣金,而那要等对账才发现
        assertThatThrownBy(() -> TotalPricePlan.of(100, 101, 0)).hasMessageContaining("佣金比例");
        assertThatThrownBy(() -> TotalPricePlan.of(100, 10, 101)).hasMessageContaining("派遣留存");
        assertThatThrownBy(() -> TotalPricePlan.of(-1, 10, 0)).hasMessageContaining("总价");
    }

    // ─────────────── 地区回退 ───────────────

    @Test
    void 从区县一路回退到全国() {
        // 320506 = 苏州市吴中区
        assertThat(RegionScope.candidates("320506"))
                .containsExactly("320506", "320500", "320000", null);
    }

    @Test
    void 本身就是市级或省级时不重复查同一个码() {
        // 查两遍同一个码不会算错,但会让"命中的是哪一级"在日志里说不清
        assertThat(RegionScope.candidates("320500"))
                .containsExactly("320500", "320000", null);
        assertThat(RegionScope.candidates("320000"))
                .containsExactly("320000", null);
    }

    @Test
    void 码不合法时只落到全国_不去猜() {
        // 与其猜它想表达哪一级,不如落到全国那条 —— 至少是明确的一条。
        // 猜错的话会静默套上另一个地区的比例
        for (String bad : List.of("", "32", "32050", "3205061", "abcdef")) {
            assertThat(RegionScope.candidates(bad))
                    .as("非法码 " + bad)
                    .containsExactly((String) null);
        }
        assertThat(RegionScope.candidates(null)).containsExactly((String) null);
    }

    // ─────────────── 老板 2026-08-06 给的个人发单公式 ───────────────

    @Test
    void 四段加起来正好等于总价_一分不多一分不少() {
        // 老板的写法:
        //   员工价 = 总价 × (1 − 佣金比例)
        //   佣金   = 总价 × 佣金比例 × (1 − 派遣留存比例)
        // 和这里的模型是同一个,只是他把员工价显式写了出来。
        //
        // **这条守的是那个恒等式。**员工价、派遣留存、服务站佣金三段
        // 加起来必须正好是总价 —— 差一分就是有一分钱没有归属,
        // 而对账时没人认领的钱最难查。
        for (long total : new long[] {100_000, 3_333, 1, 999_999, 7}) {
            for (int c : new int[] {0, 3, 10, 33, 100}) {
                for (int d : new int[] {0, 7, 30, 100}) {
                    var p = TotalPricePlan.of(total, c, d);
                    assertThat(p.workerCents() + p.dispatchRetainCents() + p.stationPoolCents())
                            .as("总价 %d 佣金%d%% 留存%d%%".formatted(total, c, d))
                            .isEqualTo(total);
                }
            }
        }
    }

    @Test
    void 员工价就是总价减佣金总额() {
        var p = TotalPricePlan.of(100_000, 10, 30);
        assertThat(p.workerCents()).isEqualTo(90_000);
        assertThat(p.stationPoolCents()).isEqualTo(7_000);
        assertThat(p.dispatchRetainCents()).isEqualTo(3_000);
    }
}
