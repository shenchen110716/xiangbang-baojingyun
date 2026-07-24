package com.xbb.job.internal;

import com.xbb.org.api.OrganizationApproved;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class OrgEventListener {

    private final ApprovedOrgRepository approvedOrgs;

    OrgEventListener(ApprovedOrgRepository approvedOrgs) {
        this.approvedOrgs = approvedOrgs;
    }

    @ApplicationModuleListener
    @Transactional(transactionManager = "jobTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(OrganizationApproved event) {
        approvedOrgs.save(new ApprovedOrg(event.orgId(), event.legalRepUserId(), event.occurredAt()));
    }
}
