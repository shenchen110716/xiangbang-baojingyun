package com.xbb.review.internal;

import com.xbb.AbstractOutboxEvent;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "outbox_event", schema = "review")
public class ReviewOutboxEvent extends AbstractOutboxEvent {

    protected ReviewOutboxEvent() { }

    public ReviewOutboxEvent(String eventId, String eventType, String payload) {
        super(eventId, eventType, payload);
    }
}
