package com.xbb.settlement.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PayPlanFactorRepository extends JpaRepository<PayPlanFactor, Long> {

    List<PayPlanFactor> findByPlanId(long planId);
}
