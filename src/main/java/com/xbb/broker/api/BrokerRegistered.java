package com.xbb.broker.api;

import java.time.Instant;

public record BrokerRegistered(long userId, Instant occurredAt) { }
