package com.xbb.broker.internal;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "invitation", schema = "broker")
public class Invitation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "broker_user_id", nullable = false)
    private long brokerUserId;

    @Column(name = "worker_user_id", nullable = false, unique = true)
    private long workerUserId;

    @Column(name = "bound_at", nullable = false)
    private Instant boundAt = Instant.now();

    protected Invitation() { }

    public Invitation(long brokerUserId, long workerUserId) {
        this.brokerUserId = brokerUserId;
        this.workerUserId = workerUserId;
    }

    public Long getId() { return id; }
    public long getBrokerUserId() { return brokerUserId; }
    public long getWorkerUserId() { return workerUserId; }
    public Instant getBoundAt() { return boundAt; }
}
