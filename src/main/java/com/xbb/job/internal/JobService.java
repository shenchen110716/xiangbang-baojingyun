package com.xbb.job.internal;

import com.xbb.job.api.JobApi;
import com.xbb.job.api.JobPosted;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
class JobService implements JobApi {

    private final JobRepository jobs;
    private final ApprovedOrgRepository approvedOrgs;
    private final ApplicationEventPublisher events;

    JobService(JobRepository jobs, ApprovedOrgRepository approvedOrgs, ApplicationEventPublisher events) {
        this.jobs = jobs;
        this.approvedOrgs = approvedOrgs;
        this.events = events;
    }

    @Override
    @Transactional("jobTransactionManager")
    public long postJob(long orgId, String title, String description, long wageCents, long callerUserId) {
        ApprovedOrg org = approvedOrgs.findById(orgId)
                .orElseThrow(() -> new IllegalStateException("组织未通过审核"));
        if (org.getLegalRepUserId() != callerUserId) {
            throw new IllegalStateException("只有组织法人代表可以发布岗位");
        }
        Job job = jobs.save(new Job(orgId, title, description, wageCents));
        events.publishEvent(new JobPosted(job.getId(), orgId, wageCents, Instant.now()));
        return job.getId();
    }

    @Override
    @Transactional(transactionManager = "jobTransactionManager", readOnly = true)
    public Optional<JobView> findJob(long jobId) {
        return jobs.findById(jobId).map(j -> new JobView(
                j.getId(), j.getOrgId(), j.getTitle(), j.getDescription(), j.getWageCents(), j.getStatus()));
    }
}
