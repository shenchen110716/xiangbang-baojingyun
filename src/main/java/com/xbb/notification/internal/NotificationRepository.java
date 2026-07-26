package com.xbb.notification.internal;

import com.xbb.notification.api.NotificationType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByRecipientUserIdOrderByIdDesc(long recipientUserId);

    Optional<Notification> findByRecipientUserIdAndTypeAndReferenceId(
            long recipientUserId, NotificationType type, Long referenceId);

    long countByRecipientUserIdAndReadAtIsNull(long recipientUserId);
}
