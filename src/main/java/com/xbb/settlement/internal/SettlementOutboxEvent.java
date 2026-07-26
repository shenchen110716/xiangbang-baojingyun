package com.xbb.settlement.internal;

import com.xbb.AbstractOutboxEvent;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** 结算域自己的 outbox 表(每个域一张,理由见 AbstractOutboxEvent)。 */
@Entity
@Table(name = "outbox_event", schema = "settlement")
public class SettlementOutboxEvent extends AbstractOutboxEvent {

    protected SettlementOutboxEvent() { }

    public SettlementOutboxEvent(String eventId, String eventType, String payload) {
        super(eventId, eventType, payload);
    }
}
