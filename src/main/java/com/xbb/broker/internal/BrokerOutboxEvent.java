package com.xbb.broker.internal;

import com.xbb.AbstractOutboxEvent;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "outbox_event", schema = "broker")
public class BrokerOutboxEvent extends AbstractOutboxEvent {

    protected BrokerOutboxEvent() { }

    public BrokerOutboxEvent(String eventId, String eventType, String payload) {
        super(eventId, eventType, payload);
    }
}
