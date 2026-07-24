package com.xbb.job.api;

import java.time.Instant;

public record ApplicationSubmitted(long applicationId, long jobId, long applicantUserId, Instant occurredAt) { }
