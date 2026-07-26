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

    JobService(JobRepository jobs, ApprovedOrgRepository approvedOrgs, JobOutboxRepository outbox, ObjectMapper json) {
        this.jobs = jobs;
        this.approvedOrgs = approvedOrgs;
        this.outbox = outbox;
        this.json = json;
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
        requireLegalRep(orgId, callerUserId, "发布岗位");
        Job job = jobs.save(new Job(orgId, title, description, wageCents, headcount));
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
        return jobs.findById(jobId).map(j -> new JobView(
                j.getId(), j.getOrgId(), j.getTitle(), j.getDescription(), j.getWageCents(),
                j.getStatus(), j.getHeadcount(), j.getFilledCount()));
    }
}
