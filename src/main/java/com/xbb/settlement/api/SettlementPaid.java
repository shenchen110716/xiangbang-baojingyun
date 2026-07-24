package com.xbb.settlement.api;

import java.time.Instant;

public record SettlementPaid(long settlementId, long workerUserId, long amountCents, Instant occurredAt) { }
