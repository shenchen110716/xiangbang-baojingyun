package com.xbb.settlement.internal;

import com.xbb.job.api.JobPosted;
import com.xbb.org.api.OrganizationApproved;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 显式命名:其它域也有同名类,默认 bean 名会撞车。 */
@Component("settlementJobEventListener")
class JobEventListener {

    private final SettlementPostedJobRepository postedJobs;
    private final SettlementApprovedOrgRepository orgs;

    JobEventListener(SettlementPostedJobRepository postedJobs, SettlementApprovedOrgRepository orgs) {
        this.postedJobs = postedJobs;
        this.orgs = orgs;
    }

    /**
     * `@EventListener` 而非 AFTER_COMMIT:事件由发布方 outbox 中继投递,
     * AFTER_COMMIT 要等中继事务提交后才跑,那时 outbox 行已是 PUBLISHED,
     * 这里再抛异常事件就永久丢了。
     */
    @EventListener
    @Transactional(transactionManager = "settlementTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(JobPosted event) {
        // 主键相同时 save 走 merge,对至少一次投递是幂等的
        postedJobs.save(new SettlementPostedJob(event.jobId(), event.orgId(), event.occurredAt()));
    }

    @EventListener
    @Transactional(transactionManager = "settlementTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(OrganizationApproved event) {
        orgs.save(new SettlementApprovedOrg(event.orgId(), event.legalRepUserId(), event.occurredAt()));
    }
}
