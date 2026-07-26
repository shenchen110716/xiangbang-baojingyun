package com.xbb.job.internal;

import com.xbb.org.api.OrganizationApproved;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import static org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT;

@Component
class OrgEventListener {

    private final ApprovedOrgRepository approvedOrgs;

    OrgEventListener(ApprovedOrgRepository approvedOrgs) {
        this.approvedOrgs = approvedOrgs;
    }

    /**
     * `@EventListener` 而非 AFTER_COMMIT:该事件由发布方的 outbox 中继投递。
     * AFTER_COMMIT 的监听器要等中继事务提交后才跑,那时 outbox 行已是 PUBLISHED,
     * 这里再抛异常事件就永久丢了(理由详见 AbstractOutboxRelay)。
     */
    @EventListener
    @Transactional(transactionManager = "jobTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(OrganizationApproved event) {
        approvedOrgs.save(new ApprovedOrg(event.orgId(), event.legalRepUserId(), event.occurredAt()));
    }
}
