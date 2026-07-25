package com.xbb.engagement.api;

import com.xbb.engagement.internal.Application;
import java.util.Optional;

public interface EngagementApi {

    record ApplicationView(long id, long jobId, long applicantUserId, Application.Status status) { }

    long apply(long jobId, long applicantUserId);

    void acceptApplication(long applicationId, long callerUserId);

    void rejectApplication(long applicationId, long callerUserId);

    Optional<ApplicationView> findApplication(long applicationId);
}
