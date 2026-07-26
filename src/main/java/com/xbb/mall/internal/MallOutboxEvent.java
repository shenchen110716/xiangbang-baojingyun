package com.xbb.mall.internal;

import com.xbb.AbstractOutboxEvent;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "outbox_event", schema = "mall")
public class MallOutboxEvent extends AbstractOutboxEvent {

    protected MallOutboxEvent() { }

    public MallOutboxEvent(String eventId, String eventType, String payload) {
        super(eventId, eventType, payload);
    }
}
