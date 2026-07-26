package com.xbb.matching.internal;

import com.xbb.matching.api.MatchingApi;
import com.xbb.matching.internal.MatchScorer.JobSnapshot;
import com.xbb.matching.internal.MatchScorer.Side;
import com.xbb.matching.internal.MatchScorer.WorkerSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

@Service
class MatchingService implements MatchingApi {

    private static final Logger log = LoggerFactory.getLogger(MatchingService.class);

    /** ε-greedy(§5.4.4):20% 曝光位强制给低数据对象。"无探索位 = 新供给永远进不来 = 平台死亡"。 */
    static final double EPSILON = 0.2;

    private final WorkerProjectionRepository workers;
    private final JobProjectionRepository jobs;
    private final MatchScorer scorer;
    private final RandomGenerator random;

    /**
     * 候选池上限。硬约束(must 标签是子集判断)现在还落在内存里做,
     * 所以真正能挡住"一次推荐扫全表"的只有这个上限。没有它,数据一涨
     * 单次请求的耗时就跟着表大小线性涨,而且是在事务里涨。
     */
    private final int poolLimit;

    MatchingService(WorkerProjectionRepository workers, JobProjectionRepository jobs,
                     MatchScorer scorer, RandomGenerator random,
                     @Value("${xbb.matching.candidate-pool-limit:1000}") int poolLimit) {
        this.workers = workers;
        this.jobs = jobs;
        this.scorer = scorer;
        this.random = random;
        this.poolLimit = poolLimit;
    }

    /**
     * 取候选池:按"最近更新优先"排序后只取上限内的行,多取一行用来判断是否被截断。
     *
     * <p>排序不是可有可无的——上限一旦生效,拿哪些行就成了策略。不排序等于
     * 让数据库随便给几行,同一个人两次刷新拿到的推荐会莫名其妙地变。
     * 选"最近更新优先"的理由:超出上限时,新岗/刚改过的岗比陈年旧岗更该被看到。
     */
    private <T> List<T> pool(java.util.function.Function<PageRequest, List<T>> fetch, String what) {
        List<T> rows = fetch.apply(PageRequest.of(0, poolLimit + 1,
                Sort.by(Sort.Order.desc("updatedAt"))));
        if (rows.size() > poolLimit) {
            // 必须留痕:静默截断会让"某个岗位没被推荐出来"看起来像算法判错,
            // 排查的人会去翻打分逻辑,而真正的原因在这里。
            log.warn("{}候选池达到上限 {} 被截断,本次推荐只覆盖最近更新的那部分。"
                    + "若长期出现,说明该做粗筛下推或分区了。", what, poolLimit);
            return rows.subList(0, poolLimit);
        }
        return rows;
    }

    @Override
    @Transactional(transactionManager = "matchingTransactionManager", readOnly = true)
    public List<MatchView> recommendJobsForWorker(long userId, int limit) {
        WorkerProjection worker = workers.findById(userId).orElse(null);
        if (worker == null) return List.of();
        WorkerSnapshot workerSnapshot = toSnapshot(worker);

        List<Candidate> candidates = new ArrayList<>();
        for (JobProjection job : pool(page -> jobs.findByOpenTrue(page).getContent(), "岗位")) {
            JobSnapshot jobSnapshot = toSnapshot(job);
            if (!scorer.passesHardConstraints(workerSnapshot, jobSnapshot)) continue;
            MatchScorer.Score score = scorer.score(workerSnapshot, jobSnapshot, Side.WORKER);
            candidates.add(new Candidate(job.getJobId(), score, isLowData(job), workerSnapshot, jobSnapshot));
        }
        return select(candidates, limit);
    }

    @Override
    @Transactional(transactionManager = "matchingTransactionManager", readOnly = true)
    public List<MatchView> recommendWorkersForJob(long jobId, int limit) {
        JobProjection job = jobs.findById(jobId).orElse(null);
        if (job == null) return List.of();
        JobSnapshot jobSnapshot = toSnapshot(job);

        List<Candidate> candidates = new ArrayList<>();
        for (WorkerProjection worker : pool(page -> workers.findAll(page).getContent(), "人才")) {
            WorkerSnapshot workerSnapshot = toSnapshot(worker);
            if (!scorer.passesHardConstraints(workerSnapshot, jobSnapshot)) continue;
            MatchScorer.Score score = scorer.score(workerSnapshot, jobSnapshot, Side.ORG);
            candidates.add(new Candidate(worker.getUserId(), score, isLowData(worker), workerSnapshot, jobSnapshot));
        }
        return select(candidates, limit);
    }

    /**
     * 排序 + ε-greedy 探索位注入。探索位从"低数据"候选里随机取,
     * 其余按得分降序填满——新人/新岗不会因为没数据被永久压在结果之外。
     */
    private List<MatchView> select(List<Candidate> candidates, int limit) {
        if (candidates.isEmpty() || limit <= 0) return List.of();

        List<Candidate> lowData = new ArrayList<>(candidates.stream().filter(Candidate::lowData).toList());
        List<Candidate> byScore = new ArrayList<>(candidates);
        byScore.sort(Comparator.comparingDouble((Candidate c) -> c.score().total()).reversed());

        int exploreQuota = (int) Math.round(limit * EPSILON);
        List<MatchView> result = new ArrayList<>();
        List<Long> taken = new ArrayList<>();

        // 先放探索位
        for (int i = 0; i < exploreQuota && !lowData.isEmpty(); i++) {
            Candidate picked = lowData.remove(random.nextInt(lowData.size()));
            taken.add(picked.targetId());
            result.add(toView(picked, true));
        }
        // 再按得分补满
        for (Candidate candidate : byScore) {
            if (result.size() >= limit) break;
            if (taken.contains(candidate.targetId())) continue;
            result.add(toView(candidate, false));
        }
        return result;
    }

    private MatchView toView(Candidate candidate, boolean explored) {
        return new MatchView(candidate.targetId(), candidate.score().total(),
                explainReason(candidate), explored);
    }

    /**
     * 取贡献最高的 2 个维度生成理由(§5.4.5 R2)。
     * 主文档理由:"蓝领信任度低,黑盒推荐必被质疑"。
     */
    private String explainReason(Candidate candidate) {
        Map<String, Double> breakdown = candidate.score().breakdown();
        if (breakdown.isEmpty()) return "新发布,推荐你看看";
        List<String> top = breakdown.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(2)
                .map(e -> describe(e.getKey(), candidate))
                .toList();
        return String.join(" · ", top);
    }

    private String describe(String dimension, Candidate candidate) {
        WorkerSnapshot worker = candidate.worker();
        JobSnapshot job = candidate.job();
        return switch (dimension) {
            case "距离" -> {
                Double score = scorer.distanceScore(worker, job);
                // 由 exp(-d/5) 反推公里数,避免把距离计算逻辑在这里抄第二遍
                double km = score == null ? 0 : -5.0 * Math.log(score);
                yield "离你 %.1f 公里".formatted(km);
            }
            case "技能" -> "你做过同类岗";
            case "信用" -> "履约记录良好";
            case "薪资" -> job.wageCents() >= (worker.expectedWageCents() == null ? 0 : worker.expectedWageCents())
                    ? "薪资达到你的期望"
                    : "薪资 %d 元".formatted(job.wageCents() / 100);
            default -> dimension;
        };
    }

    /** 低数据 = 从没设过画像标签,技能维度无从算起(新岗/新人)。 */
    private static boolean isLowData(JobProjection job) {
        return job.getMustTags().isEmpty() && job.getNiceTags().isEmpty();
    }

    private static boolean isLowData(WorkerProjection worker) {
        return worker.getTags().isEmpty();
    }

    private static WorkerSnapshot toSnapshot(WorkerProjection w) {
        return new WorkerSnapshot(w.getUserId(), w.getTags(), w.getExpectedWageCents(),
                w.getLat(), w.getLon(), w.getCreditScore());
    }

    private static JobSnapshot toSnapshot(JobProjection j) {
        return new JobSnapshot(j.getJobId(), j.getWageCents(), j.getMustTags(), j.getNiceTags(), j.getLat(), j.getLon());
    }

    private record Candidate(long targetId, MatchScorer.Score score, boolean lowData,
                              WorkerSnapshot worker, JobSnapshot job) { }
}
