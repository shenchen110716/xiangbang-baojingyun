package com.xbb.matching.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 匹配评分,纯函数(无 Spring 依赖、无副作用、可独立单测/回放)。
 * 公式一律照主文档 §5.4.3,不自己发明。
 *
 * <p>v0 只有技能/距离/薪资三个维度:信用维度要等评价域(P1),时段维度两侧都没有数据源。
 */
public class MatchScorer {

    /** 蓝领通勤衰减半径(§5.4.3:exp(-d / 5km),超 15km 基本不考虑)。 */
    private static final double DISTANCE_DECAY_KM = 5.0;

    private static final double EARTH_RADIUS_KM = 6371.0;

    public record WorkerSnapshot(long userId, Map<String, Double> tags,
                                  Long expectedWageCents, Double lat, Double lon,
                                  Double creditScore) { }

    public record JobSnapshot(long jobId, long wageCents, List<String> mustTags, List<String> niceTags,
                               Double lat, Double lon) { }

    public record Score(double total, Map<String, Double> breakdown) { }

    /**
     * 双边权重(§5.4.2)。v1 接入信用维度;时段维度两侧仍无数据源,
     * 按剩余四维重新归一化——保持维度间的相对比例不变。
     *
     * <p>工人视角原始 薪资 0.30 / 距离 0.25 / 技能 0.20 / 信用 0.15
     * <p>工厂视角原始 薪资 0.05 / 距离 0.10 / 技能 0.30 / **信用 0.40**
     *
     * <p>§5.4.5 R4:"履约信用是引擎的一等输入维度(工厂视角权重最高)"——工厂最怕放鸽子。
     */
    public enum Side {
        WORKER(0.30, 0.25, 0.20, 0.15),
        ORG(0.05, 0.10, 0.30, 0.40);

        private final double wage;
        private final double distance;
        private final double skill;
        private final double credit;

        Side(double wage, double distance, double skill, double credit) {
            double sum = wage + distance + skill + credit;
            this.wage = wage / sum;
            this.distance = distance / sum;
            this.skill = skill / sum;
            this.credit = credit / sum;
        }

        public double wageWeight() { return wage; }
        public double distanceWeight() { return distance; }
        public double skillWeight() { return skill; }
        public double creditWeight() { return credit; }
    }

    /**
     * 硬约束是过滤不是权重(§5.4.1):"把硬约束做成权重是灾难——45 岁以下若只是降权,
     * 50 岁的人会因为距离近而排上去,推给他一个他根本报不上的岗"。
     *
     * <p>v0 只实现了 must 标签全覆盖这一条;年龄/性别/时间冲突/准入分/名额这些
     * 字段当前都不存在,不假装校验。
     */
    public boolean passesHardConstraints(WorkerSnapshot worker, JobSnapshot job) {
        return worker.tags().keySet().containsAll(job.mustTags());
    }

    /** Σ(岗位要求标签 ∩ 人才标签 × 置信权重) / 岗位要求标签数;岗位无标签要求时该维度缺失。 */
    public Double skillScore(WorkerSnapshot worker, JobSnapshot job) {
        int required = job.mustTags().size() + job.niceTags().size();
        if (required == 0) return null;
        double hit = 0.0;
        for (String tag : job.mustTags()) {
            hit += worker.tags().getOrDefault(tag, 0.0);
        }
        for (String tag : job.niceTags()) {
            hit += worker.tags().getOrDefault(tag, 0.0);
        }
        return hit / required;
    }

    /** exp(-d / 5km);任一侧缺坐标时该维度缺失。 */
    public Double distanceScore(WorkerSnapshot worker, JobSnapshot job) {
        if (worker.lat() == null || worker.lon() == null || job.lat() == null || job.lon() == null) return null;
        double km = haversineKm(worker.lat(), worker.lon(), job.lat(), job.lon());
        return Math.exp(-km / DISTANCE_DECAY_KM);
    }

    /** credit / 100(§5.4.3);还没有信用记录时该维度缺失。 */
    public Double creditScore(WorkerSnapshot worker) {
        Double credit = worker.creditScore();
        return credit == null ? null : credit / 100.0;
    }

    /** 岗位 ≥ 期望 → 1.0,否则 岗位/期望("不因给太多而扣分");期望缺失时该维度缺失。 */
    public Double wageScore(WorkerSnapshot worker, JobSnapshot job) {
        Long expected = worker.expectedWageCents();
        if (expected == null || expected <= 0) return null;
        if (job.wageCents() >= expected) return 1.0;
        return (double) job.wageCents() / expected;
    }

    /**
     * 加权总分。**维度缺失时按剩余维度重新归一化**,不是当成 0 分——
     * 那会把"没填资料"惩罚成"完全不匹配",而冷启动期几乎所有人都没填。
     */
    public Score score(WorkerSnapshot worker, JobSnapshot job, Side side) {
        Map<String, Double> raw = new LinkedHashMap<>();
        Map<String, Double> weights = new LinkedHashMap<>();

        Double skill = skillScore(worker, job);
        if (skill != null) {
            raw.put("技能", skill);
            weights.put("技能", side.skillWeight());
        }
        Double distance = distanceScore(worker, job);
        if (distance != null) {
            raw.put("距离", distance);
            weights.put("距离", side.distanceWeight());
        }
        Double wage = wageScore(worker, job);
        if (wage != null) {
            raw.put("薪资", wage);
            weights.put("薪资", side.wageWeight());
        }
        Double credit = creditScore(worker);
        if (credit != null) {
            raw.put("信用", credit);
            weights.put("信用", side.creditWeight());
        }

        double weightSum = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        if (weightSum == 0.0) return new Score(0.0, Map.of());

        Map<String, Double> breakdown = new LinkedHashMap<>();
        double total = 0.0;
        for (Map.Entry<String, Double> entry : raw.entrySet()) {
            double contribution = entry.getValue() * weights.get(entry.getKey()) / weightSum;
            breakdown.put(entry.getKey(), contribution);
            total += contribution;
        }
        return new Score(total, breakdown);
    }

    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
