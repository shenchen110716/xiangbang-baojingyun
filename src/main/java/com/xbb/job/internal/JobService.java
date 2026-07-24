package com.xbb.job.internal;

import com.xbb.job.api.ApplicationSubmitted;
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
    private final ApplicationRepository applications;
    private final ApprovedOrgRepository approvedOrgs;
    private final JobVerifiedUserRepository verifiedUsers;
    private final ApplicationEventPublisher events;

    JobService(JobRepository jobs, ApplicationRepository applications,
               ApprovedOrgRepository approvedOrgs, JobVerifiedUserRepository verifiedUsers,
               ApplicationEventPublisher events) {
        this.jobs = jobs;
        this.applications = applications;
        this.approvedOrgs = approvedOrgs;
        this.verifiedUsers = verifiedUsers;
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
        events.publishEvent(new JobPosted(job.getId(), orgId, Instant.now()));
        return job.getId();
    }

    @Override
    @Transactional("jobTransactionManager")
    public long apply(long jobId, long applicantUserId) {
        if (verifiedUsers.findById(applicantUserId).isEmpty()) {
            throw new IllegalStateException("需要完成实名认证才能报名");
        }
        jobs.findById(jobId).orElseThrow(() -> new IllegalArgumentException("岗位不存在"));
        Application application = applications.save(new Application(jobId, applicantUserId));
        events.publishEvent(new ApplicationSubmitted(application.getId(), jobId, applicantUserId, Instant.now()));
        return application.getId();
    }

    @Override
    @Transactional(transactionManager = "jobTransactionManager", readOnly = true)
    public Optional<JobView> findJob(long jobId) {
        return jobs.findById(jobId).map(j -> new JobView(
                j.getId(), j.getOrgId(), j.getTitle(), j.getDescription(), j.getWageCents(), j.getStatus()));
    }
}
