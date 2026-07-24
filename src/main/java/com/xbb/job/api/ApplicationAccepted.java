package com.xbb.job.api;

import java.time.Instant;

public record ApplicationAccepted(long applicationId, long jobId, long applicantUserId, long wageCents, Instant occurredAt) { }
