package com.xbb.engagement.internal;

import com.xbb.engagement.api.ApplicationAccepted;
import com.xbb.engagement.api.ApplicationRejected;
import com.xbb.engagement.api.ApplicationSubmitted;
import com.xbb.engagement.api.EngagementApi;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
class EngagementService implements EngagementApi {

    private final ApplicationRepository applications;
    private final PostedJobRepository postedJobs;
    private final EngagementApprovedOrgRepository approvedOrgs;
    private final EngagementVerifiedUserRepository verifiedUsers;
    private final ApplicationEventPublisher events;

    EngagementService(ApplicationRepository applications, PostedJobRepository postedJobs,
                       EngagementApprovedOrgRepository approvedOrgs, EngagementVerifiedUserRepository verifiedUsers,
                       ApplicationEventPublisher events) {
        this.applications = applications;
        this.postedJobs = postedJobs;
        this.approvedOrgs = approvedOrgs;
        this.verifiedUsers = verifiedUsers;
        this.events = events;
    }

    @Override
    @Transactional("engagementTransactionManager")
    public long apply(long jobId, long applicantUserId) {
        if (verifiedUsers.findById(applicantUserId).isEmpty()) {
            throw new IllegalStateException("需要完成实名认证才能报名");
        }
        postedJobs.findById(jobId).orElseThrow(() -> new IllegalArgumentException("岗位不存在"));
        Application application = applications.save(new Application(jobId, applicantUserId));
        events.publishEvent(new ApplicationSubmitted(application.getId(), jobId, applicantUserId, Instant.now()));
        return application.getId();
    }

    @Override
    @Transactional("engagementTransactionManager")
    public void acceptApplication(long applicationId, long callerUserId) {
        Application application = applications.findById(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("报名记录不存在"));
        PostedJob job = postedJobs.findById(application.getJobId())
                .orElseThrow(() -> new IllegalArgumentException("岗位不存在"));
        ApprovedOrg org = approvedOrgs.findById(job.getOrgId())
                .orElseThrow(() -> new IllegalStateException("组织未通过审核"));
        if (org.getLegalRepUserId() != callerUserId) {
            throw new IllegalStateException("只有组织法人代表可以处理应聘");
        }
        application.accept();
        applications.save(application);
        events.publishEvent(new ApplicationAccepted(
                applicationId, job.getJobId(), application.getApplicantUserId(), job.getWageCents(), Instant.now()));
    }

    @Override
    @Transactional("engagementTransactionManager")
    public void rejectApplication(long applicationId, long callerUserId) {
        Application application = applications.findById(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("报名记录不存在"));
        PostedJob job = postedJobs.findById(application.getJobId())
                .orElseThrow(() -> new IllegalArgumentException("岗位不存在"));
        ApprovedOrg org = approvedOrgs.findById(job.getOrgId())
                .orElseThrow(() -> new IllegalStateException("组织未通过审核"));
        if (org.getLegalRepUserId() != callerUserId) {
            throw new IllegalStateException("只有组织法人代表可以处理应聘");
        }
        application.reject();
        applications.save(application);
        events.publishEvent(new ApplicationRejected(applicationId, Instant.now()));
    }

    @Override
    @Transactional(transactionManager = "engagementTransactionManager", readOnly = true)
    public Optional<ApplicationView> findApplication(long applicationId) {
        return applications.findById(applicationId).map(a -> new ApplicationView(
                a.getId(), a.getJobId(), a.getApplicantUserId(), a.getStatus()));
    }
}
