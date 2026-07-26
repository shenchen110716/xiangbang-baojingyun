package com.xbb.voice;

import com.xbb.voice.internal.SpokenNumber;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 纯函数,纯 JUnit 单测。核心断言直接对着主文档 §5.1 的例子。 */
class SpokenNumberTest {

    @Test
    void 主文档举的关键例子200和2000听起来必须明显不同() {
        // §5.1:"200/2000 在口语里是'两百'vs'两千',听觉差异远大于视觉"
        assertThat(SpokenNumber.of(200)).isEqualTo("两百");
        assertThat(SpokenNumber.of(2000)).isEqualTo("两千");
    }

    @Test
    void 二十个人念二十不念两十() {
        // §5.1:"'二十个'人"。十位用"二"不用"两"
        assertThat(SpokenNumber.of(20)).isEqualTo("二十");
    }

    @Test
    void 百千万的位首用两而不是二() {
        assertThat(SpokenNumber.of(200)).isEqualTo("两百");
        assertThat(SpokenNumber.of(2000)).isEqualTo("两千");
        assertThat(SpokenNumber.of(20000)).isEqualTo("两万");
    }

    @Test
    void 个位与十位用二() {
        assertThat(SpokenNumber.of(2)).isEqualTo("二");
        assertThat(SpokenNumber.of(12)).isEqualTo("十二");
        assertThat(SpokenNumber.of(22)).isEqualTo("二十二");
    }

    @Test
    void 一十在口语里说十() {
        assertThat(SpokenNumber.of(10)).isEqualTo("十");
        assertThat(SpokenNumber.of(15)).isEqualTo("十五");
    }

    @Test
    void 常见工价数字() {
        assertThat(SpokenNumber.of(150)).isEqualTo("一百五十");
        assertThat(SpokenNumber.of(180)).isEqualTo("一百八十");
        assertThat(SpokenNumber.of(300)).isEqualTo("三百");
    }

    @Test
    void 零的处理() {
        assertThat(SpokenNumber.of(0)).isEqualTo("零");
        assertThat(SpokenNumber.of(105)).isEqualTo("一百零五");
        assertThat(SpokenNumber.of(1005)).isEqualTo("一千零五");
    }

    @Test
    void 金额分转口语() {
        assertThat(SpokenNumber.money(20_000)).isEqualTo("两百块");
        assertThat(SpokenNumber.money(200_000)).isEqualTo("两千块");
        assertThat(SpokenNumber.money(15_000)).isEqualTo("一百五十块");
    }

    @Test
    void 逐位读会让200和2000几乎听不出差别所以不能那样念() {
        // 这条测试的意义是把设计意图钉住:如果哪天有人改成"二零零"式逐位读,
        // 这条会挂,提醒他 §5.1 的防线就废了
        String twoHundred = SpokenNumber.of(200);
        String twoThousand = SpokenNumber.of(2000);

        assertThat(twoHundred).doesNotContain("零");
        assertThat(twoThousand).doesNotContain("零");
        assertThat(twoHundred).isNotEqualTo(twoThousand);
    }
}
