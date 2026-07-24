package com.xbb.broker.api;

import java.time.Instant;

public record WorkerBound(long invitationId, long brokerUserId, long workerUserId, Instant occurredAt) { }
