package com.xbb.settlement.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PayPlanRepository extends JpaRepository<PayPlan, Long> {

    /** 岗位当前生效的方案。数据库有部分唯一索引保证最多一个。 */
    Optional<PayPlan> findByJobIdAndStatus(long jobId, PayPlan.Status status);

    List<PayPlan> findByJobIdOrderByVersionDesc(long jobId);
}
