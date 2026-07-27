package com.xbb.profile.internal;

import com.xbb.org.api.OrganizationApproved;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 落地组织只读副本,供归属校验使用。 */
@Component("profileOrgEventListener")
class OrgEventListener {

    private final ProfileApprovedOrgRepository approvedOrgs;

    OrgEventListener(ProfileApprovedOrgRepository approvedOrgs) {
        this.approvedOrgs = approvedOrgs;
    }

    /**
     * `@EventListener` 而非 AFTER_COMMIT:该事件由发布方的 outbox 中继投递,
     * 理由详见 AbstractOutboxRelay。
     */
    @EventListener
    @Transactional(transactionManager = "profileTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(OrganizationApproved event) {
        approvedOrgs.save(new ApprovedOrg(event.orgId(), event.legalRepUserId(), event.occurredAt()));
    }
}
