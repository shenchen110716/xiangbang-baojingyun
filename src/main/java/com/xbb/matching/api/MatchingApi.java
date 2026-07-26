package com.xbb.matching.api;

import java.util.List;

public interface MatchingApi {

    /**
     * @param targetId 给工人推岗位时是 jobId,给岗位推人选时是 userId
     * @param reason   可解释理由(§5.4.5 R2:"蓝领信任度低,黑盒推荐必被质疑")
     * @param explored 是否来自 ε-greedy 探索位(§5.4.4)
     */
    record MatchView(long targetId, double score, String reason, boolean explored) { }

    List<MatchView> recommendJobsForWorker(long userId, int limit);

    List<MatchView> recommendWorkersForJob(long jobId, int limit);
}
