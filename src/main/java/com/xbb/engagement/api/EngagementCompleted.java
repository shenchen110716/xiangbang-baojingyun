package com.xbb.engagement.api;

import java.time.Instant;
import java.util.UUID;

/**
 * 枢纽事件(主文档 §9.3):一个事件同时驱动三件事——评价域开启双盲评价窗、
 * 画像域履约反哺(标签升为履约验证)、结算域进入结算。
 * "它一旦出问题,飞轮就断了,因此它的载荷设计与幂等性优先级最高。"
 *
 * <p>载荷自包含(§9.1):三个消费方各取所需,都不用回查。
 *
 * <p>{@code eventId} 是 §9.1 要求的幂等键:"每个事件带 eventId,**消费方按它去重**"。
 * 有了它,outbox 重投(至少一次投递)才不会造成重复副作用。
 */
public record EngagementCompleted(String eventId, long applicationId, long jobId, long workerUserId,
                                   long orgId, long wageCents, Instant occurredAt) {

    public static EngagementCompleted of(long applicationId, long jobId, long workerUserId,
                                          long orgId, long wageCents, Instant occurredAt) {
        return new EngagementCompleted(UUID.randomUUID().toString(), applicationId, jobId,
                workerUserId, orgId, wageCents, occurredAt);
    }
}
