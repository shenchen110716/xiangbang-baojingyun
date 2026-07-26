package com.xbb.collab.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface WorkTaskRepository extends JpaRepository<WorkTask, Long> {

    List<WorkTask> findByAssigneeUserIdOrderByIdDesc(long assigneeUserId);
}
