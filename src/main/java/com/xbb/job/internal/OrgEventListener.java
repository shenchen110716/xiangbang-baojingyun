package com.xbb.job.internal;

import com.xbb.org.api.OrganizationApproved;
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

    // 同步(非 @Async)AFTER_COMMIT,理由见 org.internal.IdentityEventListener 的注释。
    @TransactionalEventListener(phase = AFTER_COMMIT)
    @Transactional(transactionManager = "jobTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(OrganizationApproved event) {
        approvedOrgs.save(new ApprovedOrg(event.orgId(), event.legalRepUserId(), event.occurredAt()));
    }
}
