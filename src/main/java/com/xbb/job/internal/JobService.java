package com.xbb.job.internal;

import com.xbb.job.api.JobApi;
import com.xbb.job.api.WageAnomaly;
import com.xbb.job.api.JobClosed;
import com.xbb.job.api.JobPosted;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
class JobService implements JobApi {

    private final JobRepository jobs;
    private final ApprovedOrgRepository approvedOrgs;
    private final WageAnomalyDetector anomalyDetector = new WageAnomalyDetector();
    private final JobOutboxRepository outbox;
    private final ObjectMapper json;

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(JobService.class);

    /** 判断发单人有没有实名。**现查不缓存** —— 岗位域那张副本表没人维护。 */
    private final com.xbb.identity.api.IdentityApi identityApi;


    JobService(JobRepository jobs, ApprovedOrgRepository approvedOrgs, JobOutboxRepository outbox,
                com.xbb.identity.api.IdentityApi identityApi, ObjectMapper json) {
        this.identityApi = identityApi;
        this.jobs = jobs;
        this.approvedOrgs = approvedOrgs;
        this.outbox = outbox;
        this.json = json;
    }

    /**
     * 空串和只有空白的一律当成"没填"。
     * 留着空串的话,"有没有工作地点"变成两种判断,展示层的退回逻辑会漏一种。
     */
    private static String trimToNull(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    private String serialize(Object event) {
        try {
            return json.writeValueAsString(event);
        } catch (Exception e) {
            // 序列化不了就别让这步业务成功——事件发不出去,下游永远补不回来
            throw new IllegalStateException("事件无法序列化: " + event, e);
        }
    }

    @Override
    @Transactional("jobTransactionManager")
    public long postJob(long orgId, String title, String description, long wageCents, long callerUserId) {
        return postJob(orgId, title, description, wageCents, 1, callerUserId);
    }

    @Override
    @Transactional("jobTransactionManager")
    public long postJob(long orgId, String title, String description, long wageCents,
                         int headcount, long callerUserId) {
        return postJob(orgId, title, description, wageCents, headcount, null, callerUserId);
    }

    @Override
    @Transactional("jobTransactionManager")
    public long postJob(long orgId, String title, String description, long wageCents,
                         int headcount, String workAddress, long callerUserId) {
        requireLegalRep(orgId, callerUserId, "发布岗位");
        Job job = jobs.save(new Job(orgId, title, description, wageCents, headcount,
                trimToNull(workAddress)));
        JobPosted posted = new JobPosted(job.getId(), orgId, wageCents, headcount, Instant.now());
        outbox.save(new JobOutboxEvent(java.util.UUID.randomUUID().toString(),
                JobPosted.class.getName(), serialize(posted)));
        return job.getId();
    }

    @Override
    @Transactional("jobTransactionManager")
    public void closeJob(long jobId, long callerUserId) {
        Job job = jobs.findById(jobId).orElseThrow(() -> new IllegalArgumentException("岗位不存在"));
        requireLegalRep(job.getOrgId(), callerUserId, "关闭岗位");

        // 已经关了就什么都不做:重复点击、客户端重试都会走到这里,
        // 但绝不能因此再发一次关闭事件,否则下游会看到同一个岗位关闭两次。
        if (!job.close(Instant.now())) {
            return;
        }
        jobs.save(job);
        JobClosed closed = JobClosed.of(jobId, job.getOrgId(),
                JobClosed.Reason.CLOSED_BY_ORG, Instant.now());
        outbox.save(new JobOutboxEvent(closed.eventId(), JobClosed.class.getName(), serialize(closed)));
    }

    @Override
    @Transactional("jobTransactionManager")
    public void fillSlot(long jobId) {
        Job job = jobs.findById(jobId).orElseThrow(() -> new IllegalArgumentException("岗位不存在"));
        boolean justFilled = job.fillOneSlot(Instant.now());
        jobs.save(job);
        if (justFilled) {
            JobClosed closed = JobClosed.of(jobId, job.getOrgId(),
                    JobClosed.Reason.HEADCOUNT_FILLED, Instant.now());
            outbox.save(new JobOutboxEvent(closed.eventId(), JobClosed.class.getName(), serialize(closed)));
        }
    }

    @Override
    @Transactional("jobTransactionManager")
    public long postJobByIndividual(long posterUserId, String title, String description,
                                     long totalPriceCents, String regionCode, String workAddress,
                                     long workerCents, long commissionCents,
                                     long dispatchRetainCents, Long dispatchOrgId) {
        // **现查身份域,不用副本。**岗位域没有已实名用户的副本 ——
        // job.verified_user 那张表在 V3 就被删了。
        //
        // (我第一次把理由写成"表存在但没人维护",**那是错的**;
        //  2026-08-07 审计时核对迁移才发现。结论没变,理由改对。)
        // **findVerifiedUser 名不副实:它返回任何用户,实名与否在 verified 字段里。**
        // 只判 isEmpty 的话未实名的人照样能发单 —— 我第一版就是这么写的,
        // 测试当场抓住了
        if (identityApi.findVerifiedUser(posterUserId)
                .filter(com.xbb.identity.api.IdentityApi.UserView::verified).isEmpty()) {
            // 发单方要付钱、要签协议、出了纠纷要找得到人。没实名这些都无从追溯
            throw new IllegalStateException("发单人未实名认证");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("请填写岗位标题");
        }
        if (totalPriceCents <= 0) {
            // 0 元的单子没有业务含义,负数更是会算出负佣金
            throw new IllegalArgumentException("总价必须为正,当前 " + totalPriceCents);
        }
        String region = regionCode == null ? "" : regionCode.trim();
        if (region.isBlank()) {
            // 没有地区就取不到佣金比例,这单结算时会卡住 ——
            // **与其那时候报错,不如发单时就拦**:那时候工人已经干完活了
            throw new IllegalArgumentException("请选择地区");
        }
        Job job = jobs.save(Job.byIndividual(posterUserId, title.trim(),
                description == null ? "" : description.trim(),
                totalPriceCents, region, trimToNull(workAddress),
                workerCents, commissionCents, dispatchRetainCents, dispatchOrgId));
        // **个人单也要进撮合与下游。**不发事件的话它只存在于岗位表里,
        // 求职端搜得到但报名之后的链路全是断的
        // **事件里带员工价,不是总价。**下游按这个数发钱;
        // 带总价的话工人会拿到含佣金的那个数
        JobPosted posted = new JobPosted(job.getId(), 0L, workerCents, 1, Instant.now());
        outbox.save(new JobOutboxEvent(java.util.UUID.randomUUID().toString(),
                JobPosted.class.getName(), serialize(posted)));
        LOG.info("个人发单:job={} 发单人={} 总价={}分 员工价={}分 佣金={}分 派遣留存={}分 地区={}",
                job.getId(), posterUserId, totalPriceCents, workerCents,
                commissionCents, dispatchRetainCents, region);
        return job.getId();
    }

    private void requireLegalRep(long orgId, long callerUserId, String action) {
        ApprovedOrg org = approvedOrgs.findById(orgId)
                .orElseThrow(() -> new IllegalStateException("组织未通过审核"));
        if (org.getLegalRepUserId() != callerUserId) {
            throw new IllegalStateException("只有组织法人代表可以" + action);
        }
    }

    @Override
    @Transactional(transactionManager = "jobTransactionManager", readOnly = true)
    public Optional<WageAnomaly> checkWageAnomaly(long orgId, long wageCents) {
        List<Long> history = jobs.findByOrgId(orgId).stream().map(Job::getWageCents).toList();
        return anomalyDetector.detect(wageCents, history);
    }

    @Override
    @Transactional(transactionManager = "jobTransactionManager", readOnly = true)
    public Optional<JobView> findJob(long jobId) {
        return jobs.findById(jobId).map(this::toView);
    }

    @Override
    @Transactional(transactionManager = "jobTransactionManager", readOnly = true)
    public List<JobView> listMyJobs(long callerUserId) {
        List<Long> orgIds = approvedOrgs.findByLegalRepUserId(callerUserId)
                .stream().map(ApprovedOrg::getOrgId).toList();
        // 一个组织都没有时直接短路。交给 `in ()` 各数据库方言表现不一致,
        // 而"空集合"是这里最常见的正常情况(绝大多数用户不是任何组织的法人代表)。
        if (orgIds.isEmpty()) {
            return List.of();
        }
        return withOrgInfo(jobs.findByOrgIdInOrderByIdDesc(orgIds));
    }

    @Override
    @Transactional(transactionManager = "jobTransactionManager", readOnly = true)
    public List<JobView> listOpenJobs(int limit) {
        return withOrgInfo(jobs.findByStatusOrderByIdDesc(Job.Status.OPEN)
                .stream().limit(Math.max(1, Math.min(limit, 100))).toList());
    }

    private JobView toView(Job j) {
        // 个人发的单没有组织,不去查副本 —— 拿 null 当 id 查会 NPE
        ApprovedOrg org = j.getOrgId() == null
                ? null : approvedOrgs.findById(j.getOrgId()).orElse(null);
        return toView(j, org);
    }

    private static JobView toView(Job j, ApprovedOrg org) {
        return new JobView(j.getId(), j.getOrgId(), j.getTitle(), j.getDescription(),
                j.getWageCents(), j.getStatus(), j.getHeadcount(), j.getFilledCount(),
                org == null ? null : org.getName(),
                org == null ? null : org.getAddress(),
                j.getWorkAddress(),
                j.getPosterUserId(), j.getTotalPriceCents(), j.getRegionCode(),
                j.getWorkerCents(), j.getCommissionCents(),
                j.getDispatchRetainCents(), j.getDispatchOrgId());
    }

    /**
     * 一次把这批岗位涉及的单位全查回来。
     * **逐条查的话首页 20 个岗位就是 21 次查询** —— 免费档 0.25 CPU 上很难看。
     */
    private List<JobView> withOrgInfo(List<Job> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        java.util.Map<Long, ApprovedOrg> byId = new java.util.HashMap<>();
        approvedOrgs.findAllById(list.stream().map(Job::getOrgId)
                        .filter(java.util.Objects::nonNull).distinct().toList())
                .forEach(o -> byId.put(o.getOrgId(), o));
        return list.stream()
                .map(j -> toView(j, j.getOrgId() == null ? null : byId.get(j.getOrgId())))
                .toList();
    }
}
