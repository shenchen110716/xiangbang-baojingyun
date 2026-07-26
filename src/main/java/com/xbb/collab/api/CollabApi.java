package com.xbb.collab.api;

import java.util.List;
import java.util.Optional;

/** §6.5.1 工作任务分派、进度、协作。内部管理工具(平台/驻厂/业务员),不是 C 端功能。 */
public interface CollabApi {

    enum TaskStatus { OPEN, CLOSED }

    record TaskView(long id, String title, String detail, long creatorUserId, long assigneeUserId,
                     Long relatedJobId, Long relatedOrgId, int progress, TaskStatus status) { }

    long createTask(String title, String detail, long creatorUserId, long assigneeUserId,
                     Long relatedJobId, Long relatedOrgId);

    /** 只有创建人或负责人能更新进度——内部工具也不该谁都能改别人的任务。 */
    void updateProgress(long taskId, long callerUserId, int progress);

    void closeTask(long taskId, long callerUserId);

    List<TaskView> myTasks(long assigneeUserId);

    Optional<TaskView> findTask(long taskId);
}
