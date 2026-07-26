package com.xbb.voice;

import com.xbb.voice.internal.ReadbackComposer;
import com.xbb.voice.internal.ReadbackComposer.JobDraft;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ReadbackComposerTest {

    @Test
    void 回读用口语化数字而不是阿拉伯数字() {
        // §5.1 的示例:"二十个普工,两百块一天"
        String readback = ReadbackComposer.compose(
                new JobDraft("普工", 20, 20_000, "包吃住"), Optional.empty());

        assertThat(readback).contains("二十个普工").contains("两百块一天").contains("包吃住");
        // 阿拉伯数字出现就说明防线破了
        assertThat(readback).doesNotContain("20").doesNotContain("200");
    }

    @Test
    void 正常单以对吗结尾等待确认() {
        String readback = ReadbackComposer.compose(
                new JobDraft("质检", 5, 25_000, null), Optional.empty());

        assertThat(readback).endsWith("对吗?");
    }

    @Test
    void 薪资异常时把质疑拼进回读() {
        // §5.1 防线②:"日薪 2000 的普工岗系统必须起疑"
        String readback = ReadbackComposer.compose(
                new JobDraft("普工", 20, 200_000, null),
                Optional.of("比贵司历史发单(约 200 元)高出 10.0 倍"));

        assertThat(readback).contains("两千块一天");
        assertThat(readback).contains("确定吗?").contains("高出");
    }

    @Test
    void 两百和两千的回读明显不同() {
        String twoHundred = ReadbackComposer.compose(
                new JobDraft("普工", 1, 20_000, null), Optional.empty());
        String twoThousand = ReadbackComposer.compose(
                new JobDraft("普工", 1, 200_000, null), Optional.empty());

        assertThat(twoHundred).contains("两百块");
        assertThat(twoThousand).contains("两千块");
    }
}
