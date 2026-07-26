package com.xbb.talent.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface TalentApi {

    record TalentView(long userId, Map<String, Double> tags, Long expectedWageCents,
                       int completedEngagements, Instant lastActiveAt, int matchedTagCount) { }

    /**
     * 按技能标签检索人才。排序:命中标签数优先,其次累计履约次数——
     * "干过并且干完了"的人比只是自称会的人更值得推荐,这正是人才库存在的意义。
     */
    List<TalentView> search(List<String> tags, int limit);

    Optional<TalentView> findTalent(long userId);
}
