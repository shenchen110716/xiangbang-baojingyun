package com.xbb.engagement.api;

import java.time.Instant;

/** orgId 是协议域生成协议时的必要主体(甲方),载荷自包含,消费方不用回查(§9.1)。 */
public record ApplicationAccepted(long applicationId, long jobId, long applicantUserId,
                                   long orgId, long wageCents, Instant occurredAt) { }
