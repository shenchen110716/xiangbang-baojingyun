package com.xbb.profile.api;

import java.time.Instant;
import java.util.List;

/**
 * 岗位画像已更新。must/nice 分层保留到事件载荷里——消费方(匹配域)必须能区分
 * "硬约束"和"加分项",合成一个标签列表就等于丢掉了这个区分(主文档 §5.2.2)。
 */
public record JobProfileUpdated(long jobId, List<String> mustTags, List<String> niceTags,
                                 double lat, double lon, Instant occurredAt) { }
