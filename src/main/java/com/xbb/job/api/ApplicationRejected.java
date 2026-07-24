package com.xbb.job.api;

import java.time.Instant;

public record ApplicationRejected(long applicationId, Instant occurredAt) { }
