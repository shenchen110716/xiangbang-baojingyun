package com.xbb.job.api;

import java.time.Instant;

public record JobPosted(long jobId, long orgId, long wageCents, int headcount, Instant occurredAt) { }
