package com.xbb.profile.api;

import java.util.List;
import java.util.Optional;

public interface ProfileApi {

    record ProfileTagView(String tagName, String source, double confidence) { }

    record JobProfileView(long jobId, List<String> mustTags, List<String> niceTags, double lat, double lon) { }

    record WorkerPreferenceView(long userId, long expectedWageCents, double lat, double lon) { }

    void submitTags(long userId, List<String> tagNames);

    List<ProfileTagView> getProfile(long userId);

    /** 岗位画像:must 进硬约束过滤,nice 进软偏好评分(主文档 §5.2.2)。 */
    void setJobProfile(long jobId, List<String> mustTags, List<String> niceTags,
                        double lat, double lon, long callerUserId);

    Optional<JobProfileView> findJobProfile(long jobId);

    void setWorkerPreference(long userId, long expectedWageCents, double lat, double lon);

    Optional<WorkerPreferenceView> findWorkerPreference(long userId);
}
