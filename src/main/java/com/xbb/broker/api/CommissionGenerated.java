package com.xbb.broker.api;

import java.time.Instant;

public record CommissionGenerated(long commissionId, long brokerUserId, long amountCents, Instant occurredAt) { }
