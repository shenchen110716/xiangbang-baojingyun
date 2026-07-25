package com.xbb.profile.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProfileTagRepository extends JpaRepository<ProfileTag, Long> {

    List<ProfileTag> findByUserId(long userId);

    Optional<ProfileTag> findByUserIdAndTagName(long userId, String tagName);
}
