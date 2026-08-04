package com.xbb.engagement.api;

import com.xbb.engagement.internal.Application;
import java.util.List;
import java.util.Optional;

public interface EngagementApi {

    record ApplicationView(long id, long jobId, long applicantUserId, Application.Status status) { }

    long apply(long jobId, long applicantUserId);

    void acceptApplication(long applicationId, long callerUserId);

    void rejectApplication(long applicationId, long callerUserId);

    /** 履约完成(ACCEPTED → COMPLETED),发布枢纽事件 EngagementCompleted。 */
    void completeApplication(long applicationId, long callerUserId);

    Optional<ApplicationView> findApplication(long applicationId);

    /** 我的报名列表。 */
    List<ApplicationView> listMyApplications(long applicantUserId);

    /**
     * 某岗位的应聘者列表。**只有该岗位所属组织的法人代表能看** ——
     * 报名记录里有手机号对应的用户 id,随便谁都能拉等于泄露求职意向。
     */
    List<ApplicationView> listJobApplicants(long jobId, long callerUserId);
}
