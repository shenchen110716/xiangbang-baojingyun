package com.xbb.fund.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdvanceRepository extends JpaRepository<Advance, Long> {

    /** 某人的未还借支,先借的先还。 */
    List<Advance> findByWorkerUserIdAndStatusOrderByIdAsc(long workerUserId, Advance.Status status);

    List<Advance> findByWorkerUserIdOrderByIdDesc(long workerUserId);

    /**
     * 这笔结算能用来抵扣的借支:**这家单位批的** + 平台垫的。
     *
     * <p>不加这个过滤的话,甲公司批的借支会从乙公司给同一个工人的付款里扣走 ——
     * 甲的钱没出,乙的工人少拿了,而两边都不会报错。
     *
     * @param orgId 这笔结算的出资单位;为 null 时只有平台垫的那些可扣
     */
    @org.springframework.data.jpa.repository.Query(
            "select a from Advance a where a.workerUserId = :worker and a.status = :status "
            + "and (a.orgId is null or a.orgId = :orgId) order by a.id asc")
    java.util.List<Advance> findDeductible(
            @org.springframework.data.repository.query.Param("worker") long workerUserId,
            @org.springframework.data.repository.query.Param("status") Advance.Status status,
            @org.springframework.data.repository.query.Param("orgId") Long orgId);
}
