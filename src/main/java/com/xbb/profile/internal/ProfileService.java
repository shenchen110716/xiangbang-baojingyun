package com.xbb.profile.internal;

import com.xbb.profile.api.ProfileApi;
import com.xbb.profile.api.ProfileUpdated;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
class ProfileService implements ProfileApi {

    private final ProfileTagRepository tags;
    private final ApplicationEventPublisher events;

    ProfileService(ProfileTagRepository tags, ApplicationEventPublisher events) {
        this.tags = tags;
        this.events = events;
    }

    @Override
    @Transactional("profileTransactionManager")
    public void submitTags(long userId, List<String> tagNames) {
        for (String tagName : tagNames) {
            if (!ProfileTag.CONTROLLED_VOCABULARY.contains(tagName)) {
                throw new IllegalArgumentException("标签不在受控词表内: " + tagName);
            }
        }
        for (String tagName : tagNames) {
            ProfileTag tag = tags.findByUserIdAndTagName(userId, tagName)
                    .map(existing -> { existing.touch(); return existing; })
                    .orElseGet(() -> new ProfileTag(userId, tagName));
            tags.save(tag);
        }
        List<ProfileUpdated.TagUpdate> updates = tagNames.stream()
                .map(tagName -> new ProfileUpdated.TagUpdate(tagName, ProfileTag.Source.SELF_REPORTED.name(), 0.4))
                .toList();
        events.publishEvent(new ProfileUpdated(userId, updates, Instant.now()));
    }

    @Override
    @Transactional(transactionManager = "profileTransactionManager", readOnly = true)
    public List<ProfileTagView> getProfile(long userId) {
        return tags.findByUserId(userId).stream()
                .map(t -> new ProfileTagView(t.getTagName(), t.getSource().name(), t.getConfidence()))
                .toList();
    }
}
