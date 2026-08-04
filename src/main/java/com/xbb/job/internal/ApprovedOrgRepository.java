package com.xbb.job.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApprovedOrgRepository extends JpaRepository<ApprovedOrg, Long> {

    /** 我是法人代表的组织(本域只读副本)。跨域查组织库会被数据库拒绝——铁律 1。 */
    List<ApprovedOrg> findByLegalRepUserId(long legalRepUserId);
}
