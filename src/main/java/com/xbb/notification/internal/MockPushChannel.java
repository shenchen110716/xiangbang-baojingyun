package com.xbb.notification.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 默认实现:只记在内存里,不接真实推送。测试可据此断言"确实调用了通道"。 */
/**
 * **只在 xbb.channel.mode=mock 时装配。**
 *
 * <p>它静默成功:不打款/不签署/不推送,却让上游认为一切正常。放到生产上,
 * 系统会扣减监管账户、把发放标成 SUCCESS、写入假的完税凭证号、通知工人"工资已发放",
 * 而钱一分没出去——错误被系统自己确认为成功并层层扩散,对账时才发现。
 * 所以它必须由配置显式打开,而不是靠"生产上记得换实现"。
 */
@ConditionalOnProperty(name = "xbb.channel.mode", havingValue = "mock")
@Component
class MockPushChannel implements NotificationChannel {

    record Sent(long recipientUserId, String title, String body) { }

    private final List<Sent> sent = new CopyOnWriteArrayList<>();

    @Override
    public void send(long recipientUserId, String title, String body) {
        sent.add(new Sent(recipientUserId, title, body));
    }

    List<Sent> sentMessages() { return sent; }
}
