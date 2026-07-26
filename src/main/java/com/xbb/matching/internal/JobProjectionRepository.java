package com.xbb.matching.internal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobProjectionRepository extends JpaRepository<JobProjection, Long> {

    /**
     * 候选池只取还在招的岗位。过滤下推到数据库,而不是全捞进内存再筛——
     * 已关闭的岗位本来就不该占候选池的名额(§5.4"不满足直接不出现")。
     */
    Page<JobProjection> findByOpenTrue(Pageable pageable);
}
