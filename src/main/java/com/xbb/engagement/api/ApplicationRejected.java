package com.xbb.engagement.api;

import java.time.Instant;

public record ApplicationRejected(long applicationId, Instant occurredAt) { }
