package com.xbb.ops.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface PlatformSettingChangeRepository extends JpaRepository<PlatformSettingChange, Long> {

    List<PlatformSettingChange> findTop50ByOrderByChangedAtDesc();

    List<PlatformSettingChange> findByKeyOrderByChangedAtDesc(String key);
}
