package com.xbb.engagement.internal;

import com.xbb.job.api.JobClosed;
import com.xbb.job.api.JobPosted;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;


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
        // **找不到岗位投影时必须抛出,不能静默返回。**
        // JobPosted 可能因为退避排在 JobClosed 之后重投;此时若把 Closed 吞掉
        // (中继随即标 PUBLISHED、永不重投),随后 JobPosted 落地建出的投影
        // 默认是 open —— 已招满/已下架的岗位就在推荐和报名里永久复活了。
        var job = postedJobs.findById(event.jobId())
                .orElseThrow(() -> new IllegalStateException(
                        "岗位 " + event.jobId() + " 的投影尚未到达,稍后重试"));
        job.close();
        postedJobs.save(job);
    }
}
