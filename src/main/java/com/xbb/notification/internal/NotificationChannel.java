package com.xbb.notification.internal;

/**
 * 送达通道防腐层(推送/短信/模版消息)。真实对接 APNs/FCM/短信网关时换实现,
 * 通知域的业务逻辑(发给谁、发什么、幂等)一行不动。
 */
public interface NotificationChannel {

    void send(long recipientUserId, String title, String body);
}
