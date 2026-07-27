package com.xbb.engagement.internal;

import com.xbb.engagement.api.ApplicationAccepted;
import com.xbb.engagement.api.ApplicationRejected;
import com.xbb.engagement.api.ApplicationSubmitted;
import com.xbb.engagement.api.EngagementApi;
import com.xbb.engagement.api.EngagementCompleted;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbb.job.api.JobApi;
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
    /** 名额扣减必须回到岗位域(§4.2"名额扣减在本域闭环"),这里只负责触发。 */
    private final JobApi jobApi;
    private final EngagementOutboxRepository outbox;
    private final ObjectMapper json;

    EngagementService(ApplicationRepository applications, PostedJobRepository postedJobs,
                       EngagementApprovedOrgRepository approvedOrgs, EngagementVerifiedUserRepository verifiedUsers,
                       SignedAgreementRepository signedAgreements, JobApi jobApi,
                       EngagementOutboxRepository outbox, ObjectMapper json) {
        this.applications = applications;
        this.postedJobs = postedJobs;
        this.approvedOrgs = approvedOrgs;
        this.verifiedUsers = verifiedUsers;
        this.signedAgreements = signedAgreements;
        this.jobApi = jobApi;
        this.outbox = outbox;
        this.json = json;
    }

    private String serialize(Object event) {
        try {
            return json.writeValueAsString(event);
        } catch (Exception e) {
            // 序列化不了就别让"履约完成"落库成功——事件发不出去,工资单就永远不会生成
            throw new IllegalStateException("事件无法序列化: " + event, e);
        }
    }

    @Override
    @Transactional("engagementTransactionManager")
    public long apply(long jobId, long applicantUserId) {
        if (verifiedUsers.findById(applicantUserId).isEmpty()) {
            throw new IllegalStateException("需要完成实名认证才能报名");
        }
        PostedJob postedJob = postedJobs.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("岗位不存在"));
        if (!postedJob.isOpen()) {
            throw new IllegalStateException("岗位已关闭,不能报名");
        }
        // 表上有 (job_id, applicant_user_id) 唯一约束兜底;这里先查一次是为了给出
        // 说得清的错误,而不是让约束冲突冒成一句笼统的"数据冲突,可能是重复提交"。
        if (applications.findByJobIdAndApplicantUserId(jobId, applicantUserId).isPresent()) {
            throw new IllegalStateException("你已经报名过这个岗位了");
        }
        Application application = applications.save(new Application(jobId, applicantUserId));
        ApplicationSubmitted submitted = new ApplicationSubmitted(application.getId(), jobId, applicantUserId, Instant.now());
        outbox.save(new EngagementOutboxEvent(java.util.UUID.randomUUID().toString(),
                ApplicationSubmitted.class.getName(), serialize(submitted)));
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
        // 状态守卫必须在扣名额**之前**。原来 fillSlot 在前、accept() 的状态检查在后,
        // 于是重复点击"录用"时:第二次的 fillSlot 已经把名额扣掉(跨域事务,立即提交、
        // 不可回滚),甚至可能因此把岗位置为 CLOSED 并发出 JobClosed;随后 accept()
        // 才发现状态不对抛异常回滚——名额凭空蒸发一个,岗位还永久关闭了。
        application.requireAcceptable();

        // **先扣名额再落录用**。两个域各自独立 DataSource,这两步落在两个事务里,
        // 做不到原子,所以顺序决定了失败时倒向哪边:
        // 先扣名额失败 → 名额白扣一个,岗位显得比实际满(少招,是运营问题);
        // 先录用失败 → 录了人却没扣名额(超招,同一个坑两个人、付两份钱)。
        // 少招可以补,超招是钱。名额不足/岗位已关会在这里直接抛出,录用不会发生。
        jobApi.fillSlot(job.getJobId());

        application.accept();
        applications.save(application);
        ApplicationAccepted accepted = new ApplicationAccepted(
                applicationId, job.getJobId(), application.getApplicantUserId(),
                job.getOrgId(), job.getWageCents(), Instant.now());
        outbox.save(new EngagementOutboxEvent(java.util.UUID.randomUUID().toString(),
                ApplicationAccepted.class.getName(), serialize(accepted)));
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
        ApplicationRejected rejected = new ApplicationRejected(applicationId, Instant.now());
        outbox.save(new EngagementOutboxEvent(java.util.UUID.randomUUID().toString(),
                ApplicationRejected.class.getName(), serialize(rejected)));
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

        // 事件与履约状态**同事务落库**,再由中继投递(§9.1)。这条事件扇出五个下游,
        // 一个都不自愈——丢了就不会再有第二次"这单干完了",工资单永远不生成。
        EngagementCompleted completed = EngagementCompleted.of(
                applicationId, job.getJobId(), application.getApplicantUserId(),
                job.getOrgId(), job.getWageCents(), Instant.now());
        outbox.save(new EngagementOutboxEvent(
                completed.eventId(), EngagementCompleted.class.getName(), serialize(completed)));
    }

    @Override
    @Transactional(transactionManager = "engagementTransactionManager", readOnly = true)
    public Optional<ApplicationView> findApplication(long applicationId) {
        return applications.findById(applicationId).map(a -> new ApplicationView(
                a.getId(), a.getJobId(), a.getApplicantUserId(), a.getStatus()));
    }
}
