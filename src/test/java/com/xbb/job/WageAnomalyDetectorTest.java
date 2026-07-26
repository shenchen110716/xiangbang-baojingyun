package com.xbb.job;

import com.xbb.job.internal.WageAnomalyDetector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 纯函数,纯 JUnit 单测。核心场景直接照主文档 §5.1 举的例子。 */
class WageAnomalyDetectorTest {

    private final WageAnomalyDetector detector = new WageAnomalyDetector();

    @Test
    void 与本厂历史持平不质疑() {
        // 历史都是 200 元,这次也报 210 元
        var anomaly = detector.detect(21_000, List.of(20_000L, 20_000L, 22_000L));

        assertThat(anomaly).isEmpty();
    }

    @Test
    void 日薪2000的普工岗必须起疑() {
        // §5.1 原话:"日薪 2000 的普工岗系统必须起疑"。该厂历史都是 200 元档
        var anomaly = detector.detect(200_000, List.of(20_000L, 20_000L, 21_000L));

        assertThat(anomaly).isPresent();
        assertThat(anomaly.get().reason()).contains("历史发单").contains("200");
    }

    @Test
    void 远低于本厂历史也要起疑() {
        // 反向同样是录入错误的高发场景(少打一个零)
        var anomaly = detector.detect(2_000, List.of(20_000L, 21_000L));

        assertThat(anomaly).isPresent();
    }

    @Test
    void 新厂没有历史时仍用绝对区间兜底() {
        // "没历史"不能成为放行一切的理由
        var tooHigh = detector.detect(900_000, List.of());
        var tooLow = detector.detect(100, List.of());
        var normal = detector.detect(25_000, List.of());

        assertThat(tooHigh).isPresent();
        assertThat(tooLow).isPresent();
        assertThat(normal).isEmpty();
    }

    @Test
    void 低于平台最低参考被质疑() {
        var anomaly = detector.detect(1_000, List.of());

        assertThat(anomaly).isPresent();
        assertThat(anomaly.get().reason()).contains("最低参考");
    }

    @Test
    void 高于平台最高参考被质疑() {
        var anomaly = detector.detect(1_000_000, List.of());

        assertThat(anomaly).isPresent();
        assertThat(anomaly.get().reason()).contains("最高参考");
    }

    @Test
    void 恰好等于绝对边界不质疑() {
        // 边界不能误伤正常单
        assertThat(detector.detect(WageAnomalyDetector.ABSOLUTE_MIN_CENTS, List.of())).isEmpty();
        assertThat(detector.detect(WageAnomalyDetector.ABSOLUTE_MAX_CENTS, List.of())).isEmpty();
    }

    @Test
    void 恰好等于历史倍数阈值不质疑() {
        // 历史 200 元,这次 600 元 = 恰好 3 倍,不质疑;再高一点才质疑
        assertThat(detector.detect(60_000, List.of(20_000L))).isEmpty();
        assertThat(detector.detect(60_001, List.of(20_000L))).isPresent();
    }

    @Test
    void 用中位数而不是平均数以免被一条离谱历史带偏() {
        // 历史里混进一条错误的 5000 元,中位数仍是 200 元档,
        // 所以正常的 210 元不该被质疑(用平均数的话基准会被拉到 1800 元)
        var anomaly = detector.detect(21_000, List.of(20_000L, 20_000L, 21_000L, 500_000L));

        assertThat(anomaly).isEmpty();
    }

    @Test
    void 同时触发多个判据时理由都带上() {
        // 既超绝对上限,又远高于本厂历史
        var anomaly = detector.detect(900_000, List.of(20_000L));

        assertThat(anomaly).isPresent();
        assertThat(anomaly.get().reason()).contains("最高参考").contains("历史发单");
    }
}
