package com.xbb.fund.internal;

/**
 * 测试辅助:让指定幂等键的下一次代发失败,以便验证"失败可重发"路径(§6.4.2)。
 * 由测试类经 @Import 显式引入,不用 @Component —— 避免被生产环境的组件扫描捞进去
 * (同 identity 的 TestCodeAccessor 的做法)。
 */
public class MockWorkCardChannelAccessor {

    private final MockWorkCardChannel channel;

    public MockWorkCardChannelAccessor(MockWorkCardChannel channel) {
        this.channel = channel;
    }

    public void failNext(String idempotencyKey, String reason) {
        channel.failNext(idempotencyKey, reason);
    }
}
