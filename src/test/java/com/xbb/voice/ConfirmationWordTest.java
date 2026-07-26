package com.xbb.voice;

import com.xbb.voice.internal.ConfirmationWord;
import com.xbb.voice.internal.ConfirmationWord.Verdict;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConfirmationWordTest {

    @Test
    void 明确肯定才放行() {
        assertThat(ConfirmationWord.judge("对")).isEqualTo(Verdict.CONFIRMED);
        assertThat(ConfirmationWord.judge("确认")).isEqualTo(Verdict.CONFIRMED);
        assertThat(ConfirmationWord.judge("没错")).isEqualTo(Verdict.CONFIRMED);
        assertThat(ConfirmationWord.judge("可以")).isEqualTo(Verdict.CONFIRMED);
    }

    @Test
    void 明确否定进入修改() {
        assertThat(ConfirmationWord.judge("不对")).isEqualTo(Verdict.REJECTED);
        assertThat(ConfirmationWord.judge("改")).isEqualTo(Verdict.REJECTED);
        assertThat(ConfirmationWord.judge("错了")).isEqualTo(Verdict.REJECTED);
    }

    @Test
    void 模糊应答不放行而是重新回读() {
        // §5.1 点名的例子:"嗯…""行吧"
        assertThat(ConfirmationWord.judge("嗯")).isEqualTo(Verdict.AMBIGUOUS);
        assertThat(ConfirmationWord.judge("行吧")).isEqualTo(Verdict.AMBIGUOUS);
        assertThat(ConfirmationWord.judge("差不多")).isEqualTo(Verdict.AMBIGUOUS);
        assertThat(ConfirmationWord.judge("应该吧")).isEqualTo(Verdict.AMBIGUOUS);
    }

    @Test
    void 不对里含对但必须判为否定() {
        // 宽松的"包含肯定词就放行"会在这里翻车,后果是把"不对"当成同意直接发单
        assertThat(ConfirmationWord.judge("不对")).isEqualTo(Verdict.REJECTED);
    }

    @Test
    void 含肯定词的模糊句子不放行() {
        // "好像可以吧"含"可以",但显然不是确认
        assertThat(ConfirmationWord.judge("好像可以吧")).isEqualTo(Verdict.AMBIGUOUS);
    }

    @Test
    void 空与null判为模糊() {
        assertThat(ConfirmationWord.judge(null)).isEqualTo(Verdict.AMBIGUOUS);
        assertThat(ConfirmationWord.judge("   ")).isEqualTo(Verdict.AMBIGUOUS);
    }

    @Test
    void 标点与空格不影响判定() {
        assertThat(ConfirmationWord.judge("对!")).isEqualTo(Verdict.CONFIRMED);
        assertThat(ConfirmationWord.judge(" 确认 ")).isEqualTo(Verdict.CONFIRMED);
    }
}
