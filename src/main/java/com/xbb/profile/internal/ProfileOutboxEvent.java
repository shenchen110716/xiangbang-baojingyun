package com.xbb.profile.internal;

import com.xbb.AbstractOutboxEvent;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "outbox_event", schema = "profile")
public class ProfileOutboxEvent extends AbstractOutboxEvent {

    protected ProfileOutboxEvent() { }

    public ProfileOutboxEvent(String eventId, String eventType, String payload) {
        super(eventId, eventType, payload);
    }
}
