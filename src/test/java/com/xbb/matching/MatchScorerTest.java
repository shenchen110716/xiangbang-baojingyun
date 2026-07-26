package com.xbb.matching;

import com.xbb.matching.internal.MatchScorer;
import com.xbb.matching.internal.MatchScorer.JobSnapshot;
import com.xbb.matching.internal.MatchScorer.Side;
import com.xbb.matching.internal.MatchScorer.WorkerSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 评分是纯函数,没必要付 @SpringBootTest 的代价——纯 JUnit 单测,边界情形也好写。
 * 公式一律照主文档 §5.4.3,不自己发明。
 */
class MatchScorerTest {

    private static final double EPS = 1e-6;

    private final MatchScorer scorer = new MatchScorer();

    // ---------- 硬约束(§5.4.1:不满足直接不出现,不是降权) ----------

    @Test
    void 岗位must标签人才全覆盖才算通过硬约束() {
        WorkerSnapshot worker = new WorkerSnapshot(1L, Map.of("叉车", 0.4), 30000L, 31.0, 121.0);
        JobSnapshot job = new JobSnapshot(10L, 30000L, List.of("叉车"), List.of(), 31.0, 121.0);

        assertThat(scorer.passesHardConstraints(worker, job)).isTrue();
    }

    @Test
    void 缺任一must标签直接被硬约束挡掉() {
        WorkerSnapshot worker = new WorkerSnapshot(1L, Map.of("叉车", 0.4), 30000L, 31.0, 121.0);
        JobSnapshot job = new JobSnapshot(10L, 30000L, List.of("叉车", "电工"), List.of(), 31.0, 121.0);

        assertThat(scorer.passesHardConstraints(worker, job)).isFalse();
    }

    @Test
    void 岗位没有must标签时任何人都通过硬约束() {
        WorkerSnapshot worker = new WorkerSnapshot(1L, Map.of(), null, null, null);
        JobSnapshot job = new JobSnapshot(10L, 30000L, List.of(), List.of("普工"), 31.0, 121.0);

        assertThat(scorer.passesHardConstraints(worker, job)).isTrue();
    }

    // ---------- 技能维度(§5.4.3:Σ(岗位要求标签 ∩ 人才标签 × 置信权重) / 岗位要求标签数) ----------

    @Test
    void 技能匹配按置信权重加权再除以岗位要求标签数() {
        // 岗位要求 2 个标签(must 叉车 + nice 质检),人才只命中 1 个,自述置信 0.4
        WorkerSnapshot worker = new WorkerSnapshot(1L, Map.of("叉车", 0.4), 30000L, 31.0, 121.0);
        JobSnapshot job = new JobSnapshot(10L, 30000L, List.of("叉车"), List.of("质检"), 31.0, 121.0);

        assertThat(scorer.skillScore(worker, job)).isCloseTo(0.4 / 2, within(EPS));
    }

    @Test
    void 履约验证置信10比自述04得分高() {
        JobSnapshot job = new JobSnapshot(10L, 30000L, List.of("叉车"), List.of(), 31.0, 121.0);
        WorkerSnapshot selfReported = new WorkerSnapshot(1L, Map.of("叉车", 0.4), 30000L, 31.0, 121.0);
        WorkerSnapshot verified = new WorkerSnapshot(2L, Map.of("叉车", 1.0), 30000L, 31.0, 121.0);

        assertThat(scorer.skillScore(verified, job)).isGreaterThan(scorer.skillScore(selfReported, job));
    }

    @Test
    void 岗位没有任何标签要求时技能维度视为缺失() {
        WorkerSnapshot worker = new WorkerSnapshot(1L, Map.of("叉车", 0.4), 30000L, 31.0, 121.0);
        JobSnapshot job = new JobSnapshot(10L, 30000L, List.of(), List.of(), 31.0, 121.0);

        // 除以 0 是没有意义的,这个维度应该退出计分而不是变成 NaN 或 0
        assertThat(scorer.skillScore(worker, job)).isNull();
    }

    // ---------- 距离维度(§5.4.3:exp(-d / 5km)) ----------

    @Test
    void 同一坐标距离得分为1() {
        WorkerSnapshot worker = new WorkerSnapshot(1L, Map.of(), 30000L, 31.2304, 121.4737);
        JobSnapshot job = new JobSnapshot(10L, 30000L, List.of(), List.of(), 31.2304, 121.4737);

        assertThat(scorer.distanceScore(worker, job)).isCloseTo(1.0, within(1e-4));
    }

    @Test
    void 距离越远得分越低() {
        WorkerSnapshot worker = new WorkerSnapshot(1L, Map.of(), 30000L, 31.0, 121.0);
        JobSnapshot near = new JobSnapshot(10L, 30000L, List.of(), List.of(), 31.02, 121.0);
        JobSnapshot far = new JobSnapshot(11L, 30000L, List.of(), List.of(), 31.5, 121.0);

        assertThat(scorer.distanceScore(worker, near)).isGreaterThan(scorer.distanceScore(worker, far));
    }

    @Test
    void 坐标缺失时距离维度视为缺失() {
        WorkerSnapshot worker = new WorkerSnapshot(1L, Map.of(), 30000L, null, null);
        JobSnapshot job = new JobSnapshot(10L, 30000L, List.of(), List.of(), 31.0, 121.0);

        assertThat(scorer.distanceScore(worker, job)).isNull();
    }

    // ---------- 薪资维度(§5.4.3:岗位 ≥ 期望 → 1.0;否则 岗位/期望) ----------

    @Test
    void 岗位薪资达到期望得满分() {
        WorkerSnapshot worker = new WorkerSnapshot(1L, Map.of(), 30000L, 31.0, 121.0);
        JobSnapshot job = new JobSnapshot(10L, 30000L, List.of(), List.of(), 31.0, 121.0);

        assertThat(scorer.wageScore(worker, job)).isCloseTo(1.0, within(EPS));
    }

    @Test
    void 岗位薪资高于期望不额外加分也不扣分() {
        WorkerSnapshot worker = new WorkerSnapshot(1L, Map.of(), 30000L, 31.0, 121.0);
        JobSnapshot job = new JobSnapshot(10L, 99999L, List.of(), List.of(), 31.0, 121.0);

        // 主文档明确"不因给太多而扣分",上限就是 1.0
        assertThat(scorer.wageScore(worker, job)).isCloseTo(1.0, within(EPS));
    }

    @Test
    void 岗位薪资低于期望按比例折算() {
        WorkerSnapshot worker = new WorkerSnapshot(1L, Map.of(), 40000L, 31.0, 121.0);
        JobSnapshot job = new JobSnapshot(10L, 30000L, List.of(), List.of(), 31.0, 121.0);

        assertThat(scorer.wageScore(worker, job)).isCloseTo(30000.0 / 40000.0, within(EPS));
    }

    @Test
    void 期望薪资缺失时薪资维度视为缺失() {
        WorkerSnapshot worker = new WorkerSnapshot(1L, Map.of(), null, 31.0, 121.0);
        JobSnapshot job = new JobSnapshot(10L, 30000L, List.of(), List.of(), 31.0, 121.0);

        assertThat(scorer.wageScore(worker, job)).isNull();
    }

    // ---------- 双边权重必须不同(§5.4.2) ----------

    @Test
    void 同一对工人岗位在两个视角下得分不同() {
        // 技能强、薪资弱的组合:工厂视角看重技能(0.667),工人视角看重薪资(0.40)
        WorkerSnapshot worker = new WorkerSnapshot(1L, Map.of("叉车", 1.0), 40000L, 31.0, 121.0);
        JobSnapshot job = new JobSnapshot(10L, 20000L, List.of("叉车"), List.of(), 31.0, 121.0);

        double forWorker = scorer.score(worker, job, Side.WORKER).total();
        double forOrg = scorer.score(worker, job, Side.ORG).total();

        assertThat(forOrg).isGreaterThan(forWorker);
    }

    @Test
    void 权重按主文档双边表归一化后各维度总和为1() {
        assertThat(Side.WORKER.wageWeight() + Side.WORKER.distanceWeight() + Side.WORKER.skillWeight())
                .isCloseTo(1.0, within(EPS));
        assertThat(Side.ORG.wageWeight() + Side.ORG.distanceWeight() + Side.ORG.skillWeight())
                .isCloseTo(1.0, within(EPS));
        // 工人第一看钱,工厂第一看能不能干(§5.4.2)
        assertThat(Side.WORKER.wageWeight()).isGreaterThan(Side.ORG.wageWeight());
        assertThat(Side.ORG.skillWeight()).isGreaterThan(Side.WORKER.skillWeight());
    }

    // ---------- 维度缺失降级 ----------

    @Test
    void 维度缺失时剩余维度权重重新归一化() {
        // 只有技能维度可算(没坐标、没期望薪资)——得分应该等于技能分本身,
        // 而不是 技能分 × 技能权重(那等于把没填资料惩罚成低分)
        WorkerSnapshot worker = new WorkerSnapshot(1L, Map.of("叉车", 1.0), null, null, null);
        JobSnapshot job = new JobSnapshot(10L, 30000L, List.of("叉车"), List.of(), 31.0, 121.0);

        MatchScorer.Score score = scorer.score(worker, job, Side.WORKER);

        assertThat(score.total()).isCloseTo(1.0, within(EPS));
        assertThat(score.breakdown()).containsOnlyKeys("技能");
    }

    @Test
    void 所有维度都缺失时得分为0且明细为空() {
        WorkerSnapshot worker = new WorkerSnapshot(1L, Map.of(), null, null, null);
        JobSnapshot job = new JobSnapshot(10L, 30000L, List.of(), List.of(), null, null);

        MatchScorer.Score score = scorer.score(worker, job, Side.WORKER);

        assertThat(score.total()).isEqualTo(0.0);
        assertThat(score.breakdown()).isEmpty();
    }

    @Test
    void 明细保留各维度的加权贡献供生成可解释理由() {
        WorkerSnapshot worker = new WorkerSnapshot(1L, Map.of("叉车", 1.0), 30000L, 31.0, 121.0);
        JobSnapshot job = new JobSnapshot(10L, 30000L, List.of("叉车"), List.of(), 31.0, 121.0);

        MatchScorer.Score score = scorer.score(worker, job, Side.WORKER);

        assertThat(score.breakdown()).containsOnlyKeys("技能", "距离", "薪资");
        assertThat(score.breakdown().values().stream().mapToDouble(Double::doubleValue).sum())
                .isCloseTo(score.total(), within(EPS));
    }
}
