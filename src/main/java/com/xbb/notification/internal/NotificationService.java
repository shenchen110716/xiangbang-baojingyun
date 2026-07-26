package com.xbb.notification.internal;

import com.xbb.notification.api.NotificationApi;
import com.xbb.notification.api.NotificationType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
class NotificationService implements NotificationApi {

    private final NotificationRepository notifications;
    private final NotificationChannel channel;

    NotificationService(NotificationRepository notifications, NotificationChannel channel) {
        this.notifications = notifications;
        this.channel = channel;
    }

    /**
     * 幂等投递:同一 (收件人, 类型, 业务对象) 只产生一条通知。
     * 事件可能被重复投递,用户不该因此收到两遍同样的消息。
     */
    @Transactional("notificationTransactionManager")
    void deliver(long recipientUserId, NotificationType type, String title, String body, Long referenceId) {
        if (notifications.findByRecipientUserIdAndTypeAndReferenceId(recipientUserId, type, referenceId)
                .isPresent()) {
            return;
        }
        Notification notification = notifications.save(
                new Notification(recipientUserId, type, title, body, referenceId));
        try {
            channel.send(recipientUserId, title, body);
            notification.markSent();
        } catch (RuntimeException e) {
            // 通道失败不该让上游业务事务回滚——通知是旁路,不是主链路
            notification.markFailed();
        }
        notifications.save(notification);
    }

    @Override
    @Transactional(transactionManager = "notificationTransactionManager", readOnly = true)
    public List<NotificationView> inbox(long userId, int limit) {
        return notifications.findByRecipientUserIdOrderByIdDesc(userId).stream()
                .limit(Math.max(0, limit))
                .map(NotificationService::toView)
                .toList();
    }

    @Override
    @Transactional("notificationTransactionManager")
    public void markRead(long notificationId, long userId) {
        Notification notification = notifications.findById(notificationId)
                .orElseThrow(() -> new IllegalArgumentException("通知不存在"));
        if (notification.getRecipientUserId() != userId) {
            throw new IllegalStateException("只能操作自己的通知");
        }
        notification.markRead();
        notifications.save(notification);
    }

    @Override
    @Transactional(transactionManager = "notificationTransactionManager", readOnly = true)
    public long unreadCount(long userId) {
        return notifications.countByRecipientUserIdAndReadAtIsNull(userId);
    }

    private static NotificationView toView(Notification n) {
        return new NotificationView(n.getId(), n.getRecipientUserId(), n.getType(),
                n.getTitle(), n.getBody(), n.getReferenceId(), n.isRead(), n.getCreatedAt());
    }
}
