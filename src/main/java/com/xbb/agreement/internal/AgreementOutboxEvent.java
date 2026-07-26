package com.xbb.agreement.internal;

import com.xbb.AbstractOutboxEvent;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "outbox_event", schema = "agreement")
public class AgreementOutboxEvent extends AbstractOutboxEvent {

    protected AgreementOutboxEvent() { }

    public AgreementOutboxEvent(String eventId, String eventType, String payload) {
        super(eventId, eventType, payload);
    }
}
