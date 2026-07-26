package com.xbb.job.api;

import com.xbb.job.internal.Job;
import java.util.Optional;

public interface JobApi {

    record JobView(long id, long orgId, String title, String description, long wageCents,
                    Job.Status status, int headcount, int filledCount) {

        public int remainingSlots() { return headcount - filledCount; }
    }

    /** 单名额岗位。多名额用带 headcount 的重载。 */
    long postJob(long orgId, String title, String description, long wageCents, long callerUserId);

    long postJob(long orgId, String title, String description, long wageCents, int headcount, long callerUserId);

    /** 法人代表手动关闭岗位。已关闭的再关一次不报错,但不会重复发关闭事件。 */
    void closeJob(long jobId, long callerUserId);

    /**
     * 占用一个名额,占满则自动关闭并发布 {@link JobClosed}。
     *
     * <p>**由履约域在录用时调用**。名额被"录用"消耗,而录用发生在履约域;
     * 但 §4.2 要求"名额扣减在本域闭环",所以扣减的不变式(不能超额、满了自动关)
     * 全部留在岗位域内部,履约域只负责触发。
     *
     * <p>方向只能是 engagement → job:反过来让岗位域订阅履约事件会和已有的
     * job → engagement 订阅成环,ModularityTests 会直接拦下。
     *
     * @throws IllegalStateException 岗位不存在、已关闭,或名额已满
     */
    void fillSlot(long jobId);

    Optional<JobView> findJob(long jobId);

    /**
     * 薪资合理性质疑(§5.1 防线②)。**只质疑不拦截**——设计是"反问",不是"拒绝",
     * 用户确认后仍可发布。语音发单会调它,表单发单同样该受这条保护。
     */
    Optional<WageAnomaly> checkWageAnomaly(long orgId, long wageCents);
}
