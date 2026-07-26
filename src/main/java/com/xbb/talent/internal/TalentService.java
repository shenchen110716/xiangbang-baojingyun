package com.xbb.talent.internal;

import com.xbb.talent.api.TalentApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
class TalentService implements TalentApi {

    private static final Logger log = LoggerFactory.getLogger(TalentService.class);

    private final TalentProfileRepository profiles;

    /** 候选池上限,理由同匹配域:标签命中还在内存里算,只有它能挡住"一次检索扫全表"。 */
    private final int poolLimit;

    TalentService(TalentProfileRepository profiles,
                   @Value("${xbb.talent.candidate-pool-limit:1000}") int poolLimit) {
        this.profiles = profiles;
        this.poolLimit = poolLimit;
    }

    @Override
    @Transactional(transactionManager = "talentTransactionManager", readOnly = true)
    public List<TalentView> search(List<String> tags, int limit) {
        if (tags == null || tags.isEmpty() || limit <= 0) return List.of();
        return candidatePool().stream()
                .map(p -> toView(p, (int) tags.stream().filter(t -> p.getTags().containsKey(t)).count()))
                .filter(v -> v.matchedTagCount() > 0)
                .sorted(Comparator
                        .comparingInt(TalentView::matchedTagCount).reversed()
                        .thenComparing(Comparator.comparingInt(TalentView::completedEngagements).reversed()))
                .limit(limit)
                .toList();
    }

    /**
     * 候选池按"履约次数多、最近活跃"优先取。上限一旦生效,取哪些人就是策略问题:
     * 工厂来人才库是要找**能干且还在干**的人,所以截断时先保住这批,
     * 而不是让数据库随便给几行。
     *
     * <p>lastActiveAt 显式 nullsLast:Postgres 的 DESC 默认把 NULL 排在最前,
     * 不指定的话"从没活跃过"的档案会挤到最前面,正好和意图相反。
     */
    private List<TalentProfile> candidatePool() {
        PageRequest page = PageRequest.of(0, poolLimit + 1, Sort.by(
                Sort.Order.desc("completedEngagements"),
                Sort.Order.desc("lastActiveAt").nullsLast()));
        List<TalentProfile> rows = profiles.findAll(page).getContent();
        if (rows.size() > poolLimit) {
            // 留痕理由同匹配域:静默截断会把"人没搜出来"伪装成标签没配对。
            log.warn("人才库候选池达到上限 {} 被截断,本次检索只覆盖履约次数最多、最近活跃的那部分。"
                    + "若长期出现,说明该把标签命中下推到数据库了。", poolLimit);
            return rows.subList(0, poolLimit);
        }
        return rows;
    }

    @Override
    @Transactional(transactionManager = "talentTransactionManager", readOnly = true)
    public Optional<TalentView> findTalent(long userId) {
        return profiles.findById(userId).map(p -> toView(p, 0));
    }

    private static TalentView toView(TalentProfile p, int matchedTagCount) {
        return new TalentView(p.getUserId(), p.getTags(), p.getExpectedWageCents(),
                p.getCompletedEngagements(), p.getLastActiveAt(), matchedTagCount);
    }
}
