package com.xbb.job.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

interface JobRepository extends JpaRepository<Job, Long> {

    List<Job> findByOrgId(long orgId);

    /** 我名下所有组织的岗位。orgIds 为空时 Spring Data 生成 `in ()`,直接返回空列表更稳。 */
    List<Job> findByOrgIdInOrderByIdDesc(Collection<Long> orgIds);

    /** 可报名的岗位。求职端要能自己浏览,不能只有算法推荐。 */
    List<Job> findByStatusOrderByIdDesc(Job.Status status);
}
