package com.xbb.profile.api;

import java.time.Instant;
import java.util.List;

/**
 * 按主文档 §9.2 事件契约的精神(自包含载荷,消费方不用回查):目前只有自述层数据,
 * 没有 expectedSalaryCents/availableSlots/location 那些字段——那些要等画像域
 * 补齐更多信息采集能力才有真实数据,不在这里假装有。
 */
public record ProfileUpdated(long userId, List<TagUpdate> tags, Instant occurredAt) {

    public record TagUpdate(String tagName, String source, double confidence) { }
}
