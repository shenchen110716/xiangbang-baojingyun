package com.xbb.broker.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StationJointRepository extends JpaRepository<StationJoint, Long> {

    /** 分账时用:这个站要把佣金分给谁。 */
    List<StationJoint> findByFromOrgIdAndStatus(long fromOrgId, StationJoint.Status status);

    List<StationJoint> findByToOrgIdAndStatus(long toOrgId, StationJoint.Status status);

    /** 站点相关的全部联合(含历史),管理界面用。 */
    List<StationJoint> findByFromOrgIdOrToOrgIdOrderByIdDesc(long fromOrgId, long toOrgId);

    Optional<StationJoint> findByFromOrgIdAndToOrgIdAndStatus(
            long fromOrgId, long toOrgId, StationJoint.Status status);
}
