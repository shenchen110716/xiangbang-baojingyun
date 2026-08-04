package com.xbb.attendance.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface WorkdayRepository extends JpaRepository<Workday, Long> {

    Optional<Workday> findByApplicationIdAndWorkDate(long applicationId, LocalDate workDate);

    List<Workday> findByApplicationIdOrderByWorkDateAsc(long applicationId);

    List<Workday> findByJobIdAndWorkDateBetweenOrderByWorkDateAscWorkerUserIdAsc(
            long jobId, LocalDate from, LocalDate to);

    List<Workday> findByWorkerUserIdAndWorkDateBetweenOrderByWorkDateDesc(
            long workerUserId, LocalDate from, LocalDate to);

    /** 计薪只取已确认的。 */
    List<Workday> findByApplicationIdAndStatus(long applicationId, Workday.Status status);
}
