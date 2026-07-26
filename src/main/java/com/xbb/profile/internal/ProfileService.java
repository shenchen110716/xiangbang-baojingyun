package com.xbb.profile.internal;

import com.xbb.ops.api.OpsApi;
import com.xbb.profile.api.JobProfileUpdated;
import com.xbb.profile.api.ProfileApi;
import com.xbb.profile.api.ProfileUpdated;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
class ProfileService implements ProfileApi {

    private final ProfileTagRepository tags;
    private final JobProfileRepository jobProfiles;
    private final WorkerPreferenceRepository workerPreferences;
    private final OpsApi opsApi;
    private final ApplicationEventPublisher events;

    ProfileService(ProfileTagRepository tags, JobProfileRepository jobProfiles,
                    WorkerPreferenceRepository workerPreferences, OpsApi opsApi,
                    ApplicationEventPublisher events) {
        this.tags = tags;
        this.jobProfiles = jobProfiles;
        this.workerPreferences = workerPreferences;
        this.opsApi = opsApi;
        this.events = events;
    }

    @Override
    @Transactional("profileTransactionManager")
    public void submitTags(long userId, List<String> tagNames) {
        tagNames.forEach(this::requireInVocabulary);
        for (String tagName : tagNames) {
            ProfileTag tag = tags.findByUserIdAndTagName(userId, tagName)
                    .map(existing -> { existing.touch(); return existing; })
                    .orElseGet(() -> new ProfileTag(userId, tagName));
            tags.save(tag);
        }
        publishProfileUpdated(userId);
    }

    @Override
    @Transactional(transactionManager = "profileTransactionManager", readOnly = true)
    public List<ProfileTagView> getProfile(long userId) {
        return tags.findByUserId(userId).stream()
                .map(t -> new ProfileTagView(t.getTagName(), t.getSource().name(), t.getConfidence()))
                .toList();
    }

    @Override
    @Transactional("profileTransactionManager")
    public void setJobProfile(long jobId, List<String> mustTags, List<String> niceTags, double lat, double lon) {
        mustTags.forEach(this::requireInVocabulary);
        niceTags.forEach(this::requireInVocabulary);
        JobProfile profile = jobProfiles.findById(jobId)
                .map(existing -> { existing.update(mustTags, niceTags, lat, lon); return existing; })
                .orElseGet(() -> new JobProfile(jobId, mustTags, niceTags, lat, lon));
        jobProfiles.save(profile);
        events.publishEvent(new JobProfileUpdated(jobId, mustTags, niceTags, lat, lon, Instant.now()));
    }

    @Override
    @Transactional(transactionManager = "profileTransactionManager", readOnly = true)
    public Optional<JobProfileView> findJobProfile(long jobId) {
        return jobProfiles.findById(jobId).map(p -> new JobProfileView(
                p.getJobId(), p.getMustTags(), p.getNiceTags(), p.getLat(), p.getLon()));
    }

    @Override
    @Transactional("profileTransactionManager")
    public void setWorkerPreference(long userId, long expectedWageCents, double lat, double lon) {
        WorkerPreference preference = workerPreferences.findById(userId)
                .map(existing -> { existing.update(expectedWageCents, lat, lon); return existing; })
                .orElseGet(() -> new WorkerPreference(userId, expectedWageCents, lat, lon));
        workerPreferences.save(preference);
        publishProfileUpdated(userId);
    }

    @Override
    @Transactional(transactionManager = "profileTransactionManager", readOnly = true)
    public Optional<WorkerPreferenceView> findWorkerPreference(long userId) {
        return workerPreferences.findById(userId).map(p -> new WorkerPreferenceView(
                p.getUserId(), p.getExpectedWageCents(), p.getLat(), p.getLon()));
    }

    /**
     * 事件载荷自包含(主文档 §9.2):标签与偏好一起发,消费方不用回查。
     * 偏好还没填时后三个字段为 null——消费方必须容忍维度缺失。
     */
    private void publishProfileUpdated(long userId) {
        List<ProfileUpdated.TagUpdate> updates = tags.findByUserId(userId).stream()
                .map(t -> new ProfileUpdated.TagUpdate(t.getTagName(), t.getSource().name(), t.getConfidence()))
                .toList();
        WorkerPreference preference = workerPreferences.findById(userId).orElse(null);
        events.publishEvent(new ProfileUpdated(
                userId, updates,
                preference == null ? null : preference.getExpectedWageCents(),
                preference == null ? null : preference.getLat(),
                preference == null ? null : preference.getLon(),
                Instant.now()));
    }

    /**
     * §5.2.1:"LLM 抽取时**只能映射到已有标签,禁止自由生成新标签**",
     * 否则"打螺丝/拧螺丝/螺丝工/装配螺丝"四个标签指同一件事,标签体系一周内爆炸。
     *
     * <p>词表原本硬编码在 ProfileTag 里(Plan6/8 都记过"等运营字典"),
     * 运营域建好后改为查字典——**运营现在可以自己增删词条,不用改代码发版**。
     */
    private void requireInVocabulary(String tagName) {
        // 停用的词条等同于不在词表内——运营停用一个词就是要它不再被使用,
        // 只是从列表里隐藏而仍允许提交,等于停用没有生效。
        boolean usable = opsApi.findItem(OpsApi.SKILL_TAG, tagName)
                .filter(OpsApi.DictItemView::enabled)
                .isPresent();
        if (!usable) {
            throw new IllegalArgumentException("标签不在受控词表内: " + tagName);
        }
    }
}
