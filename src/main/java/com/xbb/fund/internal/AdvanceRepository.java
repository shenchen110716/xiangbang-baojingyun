package com.xbb.fund.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdvanceRepository extends JpaRepository<Advance, Long> {

    /** 某人的未还借支,先借的先还。 */
    List<Advance> findByWorkerUserIdAndStatusOrderByIdAsc(long workerUserId, Advance.Status status);

    List<Advance> findByWorkerUserIdOrderByIdDesc(long workerUserId);
}
