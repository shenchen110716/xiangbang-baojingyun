package com.xbb.identity.internal;

import com.xbb.AbstractOutboxEvent;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "outbox_event", schema = "identity")
public class IdentityOutboxEvent extends AbstractOutboxEvent {

    protected IdentityOutboxEvent() { }

    public IdentityOutboxEvent(String eventId, String eventType, String payload) {
        super(eventId, eventType, payload);
    }
}
