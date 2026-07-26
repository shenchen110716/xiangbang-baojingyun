package com.xbb.review;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.ops.api.OpsApi;
import com.xbb.review.internal.ReviewTag;
import com.xbb.review.internal.ReviewTag.Direction;
import com.xbb.review.internal.ReviewTagCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * 折算表格全部照主文档 §5.3.1。
 *
 * <p>词表搬进运营字典后这组用例改成起 Spring:断言的还是同一批数字,
 * 但现在验的是**真实入库的词表**算出来的分,而不是代码里那份硬编码副本。
 */
@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class})
class ReviewTagCatalogTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    private static final double EPS = 1e-9;

    @Autowired ReviewTagCatalog catalog;
    @Autowired OpsApi ops;

    @Test
    void 全正面无负面得满分5() {
        assertThat(catalog.score(Direction.ORG_RATES_WORKER, List.of("准时到岗", "手脚麻利")))
                .isCloseTo(5.0, within(EPS));
    }

    @Test
    void 一个迟到早退轻度扣05得45() {
        assertThat(catalog.score(Direction.ORG_RATES_WORKER, List.of("迟到早退")))
                .isCloseTo(4.5, within(EPS));
    }

    @Test
    void 一个中途跑单重度扣25得25() {
        assertThat(catalog.score(Direction.ORG_RATES_WORKER, List.of("中途跑单")))
                .isCloseTo(2.5, within(EPS));
    }

    @Test
    void 一个拖欠工资重度扣25得25() {
        assertThat(catalog.score(Direction.WORKER_RATES_ORG, List.of("拖欠工资")))
                .isCloseTo(2.5, within(EPS));
    }

    @Test
    void 多个负面标签叠加扣分() {
        // 迟到早退(0.5) + 消极怠工(1.0) = 1.5,5 - 1.5 = 3.5
        assertThat(catalog.score(Direction.ORG_RATES_WORKER, List.of("迟到早退", "消极怠工")))
                .isCloseTo(3.5, within(EPS));
    }

    @Test
    void 正面标签不抵消负面扣分() {
        double withPositive = catalog.score(Direction.ORG_RATES_WORKER, List.of("准时到岗", "中途跑单"));
        double withoutPositive = catalog.score(Direction.ORG_RATES_WORKER, List.of("中途跑单"));

        assertThat(withPositive).isCloseTo(withoutPositive, within(EPS));
    }

    @Test
    void 扣到下限1不会变成负数() {
        // 中途跑单(2.5) + 消极怠工(1.0) + 不服管理(1.0) + 技能不符(1.0) = 5.5 > 5
        assertThat(catalog.score(Direction.ORG_RATES_WORKER,
                List.of("中途跑单", "消极怠工", "不服管理", "技能不符")))
                .isCloseTo(1.0, within(EPS));
    }

    @Test
    void 工厂不能用工人侧的标签评工人() {
        assertThatThrownBy(() -> catalog.score(Direction.ORG_RATES_WORKER, List.of("拖欠工资")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不属于该评价方向");
    }

    @Test
    void 工人不能用工厂侧的标签评工厂() {
        assertThatThrownBy(() -> catalog.score(Direction.WORKER_RATES_ORG, List.of("中途跑单")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不属于该评价方向");
    }

    @Test
    void 编造的标签直接报错() {
        assertThatThrownBy(() -> catalog.score(Direction.ORG_RATES_WORKER, List.of("长得好看")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 没有标签时视为满分() {
        assertThat(catalog.score(Direction.ORG_RATES_WORKER, List.of())).isCloseTo(5.0, within(EPS));
    }

    @Test
    void 中途跑单被识别为负面标签供信用分违约惩罚项使用() {
        assertThat(catalog.isNegative(Direction.ORG_RATES_WORKER, ReviewTag.MID_QUIT)).isTrue();
        assertThat(catalog.isNegative(Direction.ORG_RATES_WORKER, "准时到岗")).isFalse();
    }

    @Test
    void 两个方向的标签集互不相同() {
        assertThat(catalog.availableTags(Direction.ORG_RATES_WORKER)).contains("中途跑单")
                .doesNotContain("拖欠工资");
        assertThat(catalog.availableTags(Direction.WORKER_RATES_ORG)).contains("拖欠工资")
                .doesNotContain("中途跑单");
    }

    @Test
    void 运营调高重度权重后新评价立刻按新权重算() {
        long heavy = ops.findItem(OpsApi.REVIEW_SEVERITY_WEIGHT, "HEAVY").orElseThrow().id();
        try {
            ops.updateValue(heavy, "4.0");

            // 5 - 4.0 = 1.0(正好压到下限),原权重 2.5 时是 2.5
            assertThat(catalog.score(Direction.ORG_RATES_WORKER, List.of("中途跑单")))
                    .isCloseTo(1.0, within(EPS));
        } finally {
            ops.updateValue(heavy, "2.5");
        }
        assertThat(catalog.score(Direction.ORG_RATES_WORKER, List.of("中途跑单")))
                .isCloseTo(2.5, within(EPS));
    }

    @Test
    void 运营把某标签重新定档后按新档扣分() {
        long item = ops.findItem(OpsApi.REVIEW_TAG_ORG_RATES_WORKER, "迟到早退").orElseThrow().id();
        try {
            ops.updateAttributes(item, Map.of(OpsApi.ATTR_POLARITY, OpsApi.POLARITY_NEGATIVE,
                    OpsApi.ATTR_SEVERITY, "HEAVY"));

            assertThat(catalog.score(Direction.ORG_RATES_WORKER, List.of("迟到早退")))
                    .isCloseTo(2.5, within(EPS));
        } finally {
            ops.updateAttributes(item, Map.of(OpsApi.ATTR_POLARITY, OpsApi.POLARITY_NEGATIVE,
                    OpsApi.ATTR_SEVERITY, "LIGHT"));
        }
    }

    @Test
    void 被运营停用的标签不能再用来评价() {
        long item = ops.findItem(OpsApi.REVIEW_TAG_ORG_RATES_WORKER, "态度好").orElseThrow().id();
        try {
            ops.disableItem(item);

            assertThatThrownBy(() -> catalog.score(Direction.ORG_RATES_WORKER, List.of("态度好")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不属于该评价方向");
        } finally {
            ops.enableItem(item);
        }
    }

    @Test
    void 负面标签缺严重度时宁可报错也不静默当成不扣分() {
        long id = ops.addItem(OpsApi.REVIEW_TAG_ORG_RATES_WORKER, "配置漏了的标签", "配置漏了的标签", 99,
                Map.of(OpsApi.ATTR_POLARITY, OpsApi.POLARITY_NEGATIVE));
        try {
            assertThatThrownBy(() -> catalog.score(Direction.ORG_RATES_WORKER, List.of("准时到岗")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("严重度未配置");
        } finally {
            ops.disableItem(id);
        }
    }
}
