package com.xbb.engagement.internal;

import com.xbb.engagement.api.ApplicationAccepted;
import com.xbb.engagement.api.ApplicationRejected;
import com.xbb.engagement.api.ApplicationSubmitted;
import com.xbb.engagement.api.EngagementApi;
import com.xbb.engagement.api.EngagementCompleted;
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
    private final SignedAgreementRepository signedAgreements;
    private final ApplicationEventPublisher events;

    EngagementService(ApplicationRepository applications, PostedJobRepository postedJobs,
                       EngagementApprovedOrgRepository approvedOrgs, EngagementVerifiedUserRepository verifiedUsers,
                       SignedAgreementRepository signedAgreements, ApplicationEventPublisher events) {
        this.applications = applications;
        this.postedJobs = postedJobs;
        this.approvedOrgs = approvedOrgs;
        this.verifiedUsers = verifiedUsers;
        this.signedAgreements = signedAgreements;
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
                applicationId, job.getJobId(), application.getApplicantUserId(),
                job.getOrgId(), job.getWageCents(), Instant.now()));
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
    @Transactional("engagementTransactionManager")
    public void completeApplication(long applicationId, long callerUserId) {
        Application application = applications.findById(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("报名记录不存在"));
        PostedJob job = postedJobs.findById(application.getJobId())
                .orElseThrow(() -> new IllegalArgumentException("岗位不存在"));
        ApprovedOrg org = approvedOrgs.findById(job.getOrgId())
                .orElseThrow(() -> new IllegalStateException("组织未通过审核"));
        if (org.getLegalRepUserId() != callerUserId) {
            throw new IllegalStateException("只有组织法人代表可以确认履约完成");
        }
        // 前置门禁(§6.2):"协议签署是履约进入'到岗'的前置门禁,没签不让到岗"。
        // 本项目没有独立的"到岗"态(见 Plan7 记录),门禁落在最接近的终态"确认完成"上。
        // 只对"已录用"生效:状态本身就不对的(比如还没录用)应该报状态错误,
        // 而不是被门禁抢先报"协议没签"——那会把真正的问题藏起来。
        if (application.getStatus() == Application.Status.ACCEPTED
                && signedAgreements.findById(applicationId).isEmpty()) {
            throw new IllegalStateException("劳务协议尚未签署,不能确认履约完成");
        }
        application.complete();
        applications.save(application);
        events.publishEvent(EngagementCompleted.of(
                applicationId, job.getJobId(), application.getApplicantUserId(),
                job.getOrgId(), job.getWageCents(), Instant.now()));
    }

    @Override
    @Transactional(transactionManager = "engagementTransactionManager", readOnly = true)
    public Optional<ApplicationView> findApplication(long applicationId) {
        return applications.findById(applicationId).map(a -> new ApplicationView(
                a.getId(), a.getJobId(), a.getApplicantUserId(), a.getStatus()));
    }
}
