package com.xbb.job.api;

import com.xbb.job.internal.Job;
import java.util.Optional;

public interface JobApi {

    record JobView(long id, long orgId, String title, String description, long wageCents, Job.Status status) { }

    long postJob(long orgId, String title, String description, long wageCents, long callerUserId);

    Optional<JobView> findJob(long jobId);

    /**
     * 薪资合理性质疑(§5.1 防线②)。**只质疑不拦截**——设计是"反问",不是"拒绝",
     * 用户确认后仍可发布。语音发单会调它,表单发单同样该受这条保护。
     */
    Optional<WageAnomaly> checkWageAnomaly(long orgId, long wageCents);
}
