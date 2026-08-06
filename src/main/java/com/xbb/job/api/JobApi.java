package com.xbb.job.api;

import com.xbb.job.internal.Job;
import java.util.List;
import java.util.Optional;

public interface JobApi {

    /**
     * @param orgName 用工单位名称。**可能为 null** —— 副本还没到,或旧载荷里没有
     * @param orgAddress 单位注册地址,可能为 null
     * @param workAddress 这个岗位自己的工作地点,可能为 null。
     *                    **为空时由展示层退回 orgAddress** —— 在后端抄一份的话,
     *                    单位改了地址这些岗位还留着旧的,而且分不清哪个是抄来的
     */
    record JobView(long id, long orgId, String title, String description, long wageCents,
                    Job.Status status, int headcount, int filledCount,
                    String orgName, String orgAddress, String workAddress) {

        public int remainingSlots() { return headcount - filledCount; }
    }

    /** 单名额岗位。多名额用带 headcount 的重载。 */
    long postJob(long orgId, String title, String description, long wageCents, long callerUserId);

    long postJob(long orgId, String title, String description, long wageCents, int headcount, long callerUserId);

    /**
     * 带工作地点发岗。**每个岗位可以在不同地方** ——
     * 同一家单位在几个工地同时开工是常态,只有单位注册地址的话工人会跑错地方。
     *
     * @param workAddress 可空;只有空白也当作没填
     */
    long postJob(long orgId, String title, String description, long wageCents, int headcount,
                    String workAddress, long callerUserId);

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

    /** 我名下组织发布的岗位。企业端首页要它。 */
    List<JobView> listMyJobs(long callerUserId);

    /** 开放中的岗位。求职端浏览用——此前只有算法推荐,没设画像就什么都看不到。 */
    List<JobView> listOpenJobs(int limit);

    /**
     * 薪资合理性质疑(§5.1 防线②)。**只质疑不拦截**——设计是"反问",不是"拒绝",
     * 用户确认后仍可发布。语音发单会调它,表单发单同样该受这条保护。
     */
    Optional<WageAnomaly> checkWageAnomaly(long orgId, long wageCents);
}
