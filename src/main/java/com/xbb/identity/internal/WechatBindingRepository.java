package com.xbb.identity.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WechatBindingRepository extends JpaRepository<WechatBinding, String> {
    Optional<WechatBinding> findByUserId(long userId);
}
