package com.xbb.agreement.api;

import java.time.Instant;

public record AgreementGenerated(long agreementId, long applicationId, long workerUserId,
                                  long orgId, String contentHash, Instant occurredAt) { }
