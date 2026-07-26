package com.xbb.notification.internal;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 默认实现:只记在内存里,不接真实推送。测试可据此断言"确实调用了通道"。 */
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
