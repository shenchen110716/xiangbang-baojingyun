package com.xbb.notification.internal;

import com.xbb.notification.api.NotificationType;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "notification", schema = "notification")
public class Notification {

    public enum Status { PENDING, SENT, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recipient_user_id", nullable = false)
    private long recipientUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private NotificationType type;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, length = 500)
    private String body;

    /** 业务对象 id(协议/发放/佣金…),配合 type 做幂等键。 */
    @Column(name = "reference_id")
    private Long referenceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Notification() { }

    public Notification(long recipientUserId, NotificationType type, String title,
                         String body, Long referenceId) {
        this.recipientUserId = recipientUserId;
        this.type = type;
        this.title = title;
        this.body = body;
        this.referenceId = referenceId;
    }

    public void markSent() { this.status = Status.SENT; }

    public void markFailed() { this.status = Status.FAILED; }

    public void markRead() {
        if (this.readAt == null) this.readAt = Instant.now();
    }

    public Long getId() { return id; }
    public long getRecipientUserId() { return recipientUserId; }
    public NotificationType getType() { return type; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public Long getReferenceId() { return referenceId; }
    public Status getStatus() { return status; }
    public boolean isRead() { return readAt != null; }
    public Instant getCreatedAt() { return createdAt; }
}
