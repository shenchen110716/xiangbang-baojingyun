package com.xbb.job.api;

import com.xbb.job.internal.Job;
import java.util.List;
import java.util.Optional;

public interface JobApi {

    /**
     * @param orgName 用工单位名称。**可能为 null** —— 副本还没到,或旧载荷里没有
     * @param orgId <b>个人发单时为 null</b> —— 和 posterUserId 恰好有一个非空
     * @param posterUserId 个人发单方,单位发单时为 null
     * @param totalPriceCents 总价模式的总价;按小时/按天计薪的岗位为 null
     * @param regionCode 国标行政区划代码,佣金比例按「类目 + 地区」配
     * @param workerCents 员工价。<b>发单时就定死了</b> ——
     *                    工人是看着这个数接的,后来改比例不该让他少拿
     * @param orgAddress 单位注册地址,可能为 null
     * @param workAddress 这个岗位自己的工作地点,可能为 null。
     *                    **为空时由展示层退回 orgAddress** —— 在后端抄一份的话,
     *                    单位改了地址这些岗位还留着旧的,而且分不清哪个是抄来的
     */
    record JobView(long id, Long orgId, String title, String description, long wageCents,
                    Job.Status status, int headcount, int filledCount,
                    String orgName, String orgAddress, String workAddress,
                    Long posterUserId, Long totalPriceCents, String regionCode,
                    Long workerCents, Long commissionCents, Long dispatchRetainCents,
                    Long dispatchOrgId) {

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

    /**
     * 个人发单(老板 2026-08-06)。<b>只填总价</b>,员工价和佣金由平台按
     * 「类目 + 地区」的比例算出来。
     *
     * <p>不给个人造一个"个人组织" —— organization 上有一串针对企业的约束,
     * 硬塞进去要么放宽那些约束、要么填假数据。
     *
     * @param regionCode 国标行政区划代码,<b>必填且必须是选出来的</b>
     * @param workerCents 员工价等四个数由调用方(控制器)先问经纪人域算好。
     *                    <b>不在这里算</b>:比例配在经纪人域,而 job → broker
     *                    会闭合一个模块环。数据库上有 CHECK 保证三段加起来正好是总价 ——
     *                    调用方传一组对不上的数字会被当场拒绝
     */
    long postJobByIndividual(long posterUserId, String title, String description,
                              long totalPriceCents, String regionCode, String workAddress,
                              long workerCents, long commissionCents,
                              long dispatchRetainCents, Long dispatchOrgId);

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
