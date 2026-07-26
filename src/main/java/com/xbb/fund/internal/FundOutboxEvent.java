package com.xbb.fund.internal;

import com.xbb.AbstractOutboxEvent;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "outbox_event", schema = "fund")
public class FundOutboxEvent extends AbstractOutboxEvent {

    protected FundOutboxEvent() { }

    public FundOutboxEvent(String eventId, String eventType, String payload) {
        super(eventId, eventType, payload);
    }
}
