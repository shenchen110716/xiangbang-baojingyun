package com.xbb.org.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface OrganizationRepository extends JpaRepository<Organization, Long> {

    /** 我作为法人代表的组织。界面上「我能给哪些组织发岗」全靠这个。 */
    List<Organization> findByLegalRepUserIdOrderByIdDesc(long legalRepUserId);

    /** 待审核队列,平台运维用。 */
    List<Organization> findByStatusOrderByIdAsc(Organization.Status status);
}
