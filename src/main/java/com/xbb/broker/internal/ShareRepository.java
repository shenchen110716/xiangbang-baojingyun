package com.xbb.broker.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ShareRepository extends JpaRepository<Share, Long> {
    Optional<Share> findByCode(String code);
    Optional<Share> findBySharerUserIdAndTargetTypeAndTargetId(long sharerUserId, String targetType, long targetId);
}
