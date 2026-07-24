package com.xbb.broker.internal;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "broker", schema = "broker")
public class Broker {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt = Instant.now();

    protected Broker() { }

    public Broker(long userId) {
        this.userId = userId;
    }

    public Long getUserId() { return userId; }
    public Instant getRegisteredAt() { return registeredAt; }
}
