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
    /**
     * 按标签找人。<b>要 callerUserId</b> ——
     * 2026-08-07 审计发现此前谁都能翻:任何注册用户按编号就能拿到别人的
     * 期望薪资、履约次数、最近活跃。人才库是给用工方找人的,不是公开名录。
     */
    List<TalentView> search(List<String> tags, int limit, long callerUserId);

    /**
     * 看某个人的档案。可见范围:<b>本人、平台运维、任一已审核组织的法人代表</b>。
     * 其余人拿到 empty(控制器转成 404) —— 不可见就当不存在(铁律 5.1)。
     */
    Optional<TalentView> findTalent(long userId, long callerUserId);
}
