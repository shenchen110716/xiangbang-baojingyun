package com.xbb.settlement.api;

import java.time.Instant;

public record SettlementVoided(long settlementId, String reason, Instant occurredAt) { }
