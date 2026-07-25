package com.xbb.engagement.internal;

import com.xbb.org.api.OrganizationApproved;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import static org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT;

// 显式命名:job 域也有个同名类 OrgEventListener,默认 bean 名会撞车
@Component("engagementOrgEventListener")
class OrgEventListener {

    private final EngagementApprovedOrgRepository approvedOrgs;

    OrgEventListener(EngagementApprovedOrgRepository approvedOrgs) {
        this.approvedOrgs = approvedOrgs;
    }

    // 同步(非 @Async)AFTER_COMMIT,理由见 org.internal.IdentityEventListener 的注释。
    @TransactionalEventListener(phase = AFTER_COMMIT)
    @Transactional(transactionManager = "engagementTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(OrganizationApproved event) {
        approvedOrgs.save(new ApprovedOrg(event.orgId(), event.legalRepUserId(), event.occurredAt()));
    }
}
