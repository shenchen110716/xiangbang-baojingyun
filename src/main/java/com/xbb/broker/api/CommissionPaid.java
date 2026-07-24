package com.xbb.broker.api;

import java.time.Instant;

public record CommissionPaid(long commissionId, long brokerUserId, long amountCents, Instant occurredAt) { }
