package com.xbb.collab.internal;

import com.xbb.collab.api.CollabApi;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
class CollabService implements CollabApi {

    private final WorkTaskRepository tasks;

    CollabService(WorkTaskRepository tasks) {
        this.tasks = tasks;
    }

    @Override
    @Transactional("collabTransactionManager")
    public long createTask(String title, String detail, long creatorUserId, long assigneeUserId,
                            Long relatedJobId, Long relatedOrgId) {
        return tasks.save(new WorkTask(title, detail, creatorUserId, assigneeUserId,
                relatedJobId, relatedOrgId)).getId();
    }

    @Override
    @Transactional("collabTransactionManager")
    public void updateProgress(long taskId, long callerUserId, int progress) {
        WorkTask task = load(taskId, callerUserId);
        task.updateProgress(progress);
        tasks.save(task);
    }

    @Override
    @Transactional("collabTransactionManager")
    public void closeTask(long taskId, long callerUserId) {
        WorkTask task = load(taskId, callerUserId);
        task.close();
        tasks.save(task);
    }

    @Override
    @Transactional(transactionManager = "collabTransactionManager", readOnly = true)
    public List<TaskView> myTasks(long assigneeUserId) {
        return tasks.findByAssigneeUserIdOrderByIdDesc(assigneeUserId).stream()
                .map(CollabService::toView).toList();
    }

    @Override
    @Transactional(transactionManager = "collabTransactionManager", readOnly = true)
    public Optional<TaskView> findTask(long taskId) {
        return tasks.findById(taskId).map(CollabService::toView);
    }

    private WorkTask load(long taskId, long callerUserId) {
        WorkTask task = tasks.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在"));
        if (!task.canBeManagedBy(callerUserId)) {
            throw new IllegalStateException("只有创建人或负责人可以操作这个任务");
        }
        return task;
    }

    private static TaskView toView(WorkTask t) {
        return new TaskView(t.getId(), t.getTitle(), t.getDetail(), t.getCreatorUserId(),
                t.getAssigneeUserId(), t.getRelatedJobId(), t.getRelatedOrgId(),
                t.getProgress(), t.getStatus());
    }
}
