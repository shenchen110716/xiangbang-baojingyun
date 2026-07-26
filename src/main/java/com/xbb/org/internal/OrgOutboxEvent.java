package com.xbb.org.internal;

import com.xbb.AbstractOutboxEvent;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "outbox_event", schema = "org")
public class OrgOutboxEvent extends AbstractOutboxEvent {

    protected OrgOutboxEvent() { }

    public OrgOutboxEvent(String eventId, String eventType, String payload) {
        super(eventId, eventType, payload);
    }
}
