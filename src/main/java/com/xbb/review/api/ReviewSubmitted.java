package com.xbb.review.api;

import java.time.Instant;

public record ReviewSubmitted(long reviewId, long applicationId, long raterUserId,
                               double score, Instant occurredAt) { }
