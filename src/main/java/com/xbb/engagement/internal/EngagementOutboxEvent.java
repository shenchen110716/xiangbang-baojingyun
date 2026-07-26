package com.xbb.engagement.internal;

import com.xbb.AbstractOutboxEvent;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "outbox_event", schema = "engagement")
public class EngagementOutboxEvent extends AbstractOutboxEvent {

    protected EngagementOutboxEvent() { }

    public EngagementOutboxEvent(String eventId, String eventType, String payload) {
        super(eventId, eventType, payload);
    }
}
