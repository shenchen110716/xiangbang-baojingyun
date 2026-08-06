package com.xbb.org.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface OrganizationRepository extends JpaRepository<Organization, Long> {

    /** 我作为法人代表的组织。界面上「我能给哪些组织发岗」全靠这个。 */
    List<Organization> findByLegalRepUserIdOrderByIdDesc(long legalRepUserId);

    /**
     * 这个人是不是已经有个人服务站了。
     * 用 exists 而不是取回实体 —— 只需要一个布尔值。
     */
    @org.springframework.data.jpa.repository.Query(
            "select count(o) > 0 from Organization o where o.legalRepUserId = :userId "
            + "and o.subjectType = com.xbb.org.api.SubjectType.INDIVIDUAL")
    boolean existsIndividualOf(@org.springframework.data.repository.query.Param("userId") long userId);

    /** 待审核队列,平台运维用。 */
    List<Organization> findByStatusOrderByIdAsc(Organization.Status status);
}
