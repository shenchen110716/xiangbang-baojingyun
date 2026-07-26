package com.xbb.job.internal;

import com.xbb.AbstractOutboxEvent;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "outbox_event", schema = "job")
public class JobOutboxEvent extends AbstractOutboxEvent {

    protected JobOutboxEvent() { }

    public JobOutboxEvent(String eventId, String eventType, String payload) {
        super(eventId, eventType, payload);
    }
}
