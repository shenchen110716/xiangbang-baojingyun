package com.xbb.attendance.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkdayChangeRepository extends JpaRepository<WorkdayChange, Long> {

    List<WorkdayChange> findByWorkdayIdOrderByChangedAtDesc(long workdayId);
}
