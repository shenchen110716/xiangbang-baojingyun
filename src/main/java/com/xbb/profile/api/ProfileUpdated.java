package com.xbb.profile.api;

import java.time.Instant;
import java.util.List;

/**
 * 按主文档 §9.2 事件契约(自包含载荷,消费方不用回查)。
 * expectedWageCents/lat/lon 允许为 null:工人可能只提交了标签还没填期望薪资与坐标,
 * 消费方(匹配域)必须能容忍维度缺失,不能把"没填资料"当成"完全不匹配"——
 * 冷启动期几乎所有人都没填。
 * (仍然没有 availableSlots:岗位侧没有工期/时段字段,两侧都缺数据源,不假装有。)
 */
public record ProfileUpdated(long userId, List<TagUpdate> tags,
                              Long expectedWageCents, Double lat, Double lon,
                              Instant occurredAt) {

    public record TagUpdate(String tagName, String source, double confidence) { }
}
