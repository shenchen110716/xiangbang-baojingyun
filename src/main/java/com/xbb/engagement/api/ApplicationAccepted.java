package com.xbb.engagement.api;

import java.time.Instant;

public record ApplicationAccepted(long applicationId, long jobId, long applicantUserId, long wageCents, Instant occurredAt) { }
