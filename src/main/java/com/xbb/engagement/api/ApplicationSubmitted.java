package com.xbb.engagement.api;

import java.time.Instant;

public record ApplicationSubmitted(long applicationId, long jobId, long applicantUserId, Instant occurredAt) { }
