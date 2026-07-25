package com.xbb.profile.api;

import java.util.List;

public interface ProfileApi {

    record ProfileTagView(String tagName, String source, double confidence) { }

    void submitTags(long userId, List<String> tagNames);

    List<ProfileTagView> getProfile(long userId);
}
