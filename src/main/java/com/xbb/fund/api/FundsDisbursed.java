package com.xbb.fund.api;

import java.time.Instant;

public record FundsDisbursed(long payoutId, long settlementId, long payeeUserId,
                              long amountCents, Instant occurredAt) { }
