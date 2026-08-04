package com.xbb.ops.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface PlatformSettingRepository extends JpaRepository<PlatformSetting, String> {

    List<PlatformSetting> findAllByOrderByCategoryAscKeyAsc();
}
