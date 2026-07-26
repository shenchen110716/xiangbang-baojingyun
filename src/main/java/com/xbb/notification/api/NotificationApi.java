package com.xbb.notification.api;

import java.time.Instant;
import java.util.List;

public interface NotificationApi {

    record NotificationView(long id, long recipientUserId, NotificationType type,
                             String title, String body, Long referenceId,
                             boolean read, Instant createdAt) { }

    List<NotificationView> inbox(long userId, int limit);

    void markRead(long notificationId, long userId);

    long unreadCount(long userId);
}
