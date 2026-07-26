package com.xbb.talent.internal;

import com.xbb.talent.api.TalentApi;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
class TalentService implements TalentApi {

    private final TalentProfileRepository profiles;

    TalentService(TalentProfileRepository profiles) {
        this.profiles = profiles;
    }

    @Override
    @Transactional(transactionManager = "talentTransactionManager", readOnly = true)
    public List<TalentView> search(List<String> tags, int limit) {
        if (tags == null || tags.isEmpty() || limit <= 0) return List.of();
        // 全表扫 + 内存排序:量大需要倒排索引,当前没有真实数据量,不提前优化
        return profiles.findAll().stream()
                .map(p -> toView(p, (int) tags.stream().filter(t -> p.getTags().containsKey(t)).count()))
                .filter(v -> v.matchedTagCount() > 0)
                .sorted(Comparator
                        .comparingInt(TalentView::matchedTagCount).reversed()
                        .thenComparing(Comparator.comparingInt(TalentView::completedEngagements).reversed()))
                .limit(limit)
                .toList();
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
