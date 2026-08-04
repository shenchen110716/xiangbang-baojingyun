package com.xbb.engagement.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApplicationRepository extends JpaRepository<Application, Long> {

    Optional<Application> findByJobIdAndApplicantUserId(long jobId, long applicantUserId);

    /** 我报过的名。求职端「我的报名」。 */
    List<Application> findByApplicantUserIdOrderByIdDesc(long applicantUserId);

    /** 某岗位的应聘者。企业端要它才能录用——此前只有算法推荐,那是另一回事。 */
    List<Application> findByJobIdOrderByIdAsc(long jobId);
}
