package com.xbb.engagement.internal;

import com.xbb.job.api.JobClosed;
import com.xbb.job.api.JobPosted;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import static org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT;

// 显式命名:settlement 域也有个同名类 JobEventListener,默认 bean 名会撞车
@Component("engagementJobEventListener")
class JobEventListener {

    private final PostedJobRepository postedJobs;

    JobEventListener(PostedJobRepository postedJobs) {
        this.postedJobs = postedJobs;
    }

    /**
     * `@EventListener` 而非 AFTER_COMMIT:该事件由发布方的 outbox 中继投递。
     * AFTER_COMMIT 的监听器要等中继事务提交后才跑,那时 outbox 行已是 PUBLISHED,
     * 这里再抛异常事件就永久丢了(理由详见 AbstractOutboxRelay)。
     */
    @EventListener
    @Transactional(transactionManager = "engagementTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(JobPosted event) {
        // **不能直接 new 一个覆盖整行**:那会把 open 重置回 true。
        // 投递是至少一次的,JobPosted 在 JobClosed 之后重投一次,已关闭的岗位就复活了,
        // 工人又能报名进来。所以是"有则更新、无则新建"。
        PostedJob job = postedJobs.findById(event.jobId())
                .orElseGet(() -> new PostedJob(event.jobId(), event.orgId(), event.wageCents()));
        job.updateBasics(event.orgId(), event.wageCents());
        postedJobs.save(job);
    }

    /**
     * `@EventListener` 而非 AFTER_COMMIT:该事件由发布方的 outbox 中继投递。
     * AFTER_COMMIT 的监听器要等中继事务提交后才跑,那时 outbox 行已是 PUBLISHED,
     * 这里再抛异常事件就永久丢了(理由详见 AbstractOutboxRelay)。
     */
    @EventListener
    @Transactional(transactionManager = "engagementTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(JobClosed event) {
        // 岗位可能还没投影过来(事件乱序/投影丢失),那就没什么可关的——
        // 不新建一条"已关闭"的空投影,那会让后续 JobPosted 覆盖成 open。
        postedJobs.findById(event.jobId()).ifPresent(job -> {
            job.close();
            postedJobs.save(job);
        });
    }
}
