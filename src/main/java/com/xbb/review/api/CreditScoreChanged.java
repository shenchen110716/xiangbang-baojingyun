package com.xbb.review.api;

import java.time.Instant;

/** §4.2:评价域"只发布'信用分已变更'事件",不被别人直接读表。 */
public record CreditScoreChanged(long userId, double oldScore, double newScore,
                                  String reason, Instant occurredAt) { }
