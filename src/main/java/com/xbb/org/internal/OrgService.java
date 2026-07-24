package com.xbb.org.internal;

import com.xbb.org.api.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
class OrgService implements OrgApi {

    private final OrganizationRepository orgs;
    private final VerifiedUserRepository verifiedUsers;
    private final ApplicationEventPublisher events;

    OrgService(OrganizationRepository orgs, VerifiedUserRepository verifiedUsers,
               ApplicationEventPublisher events) {
        this.orgs = orgs;
        this.verifiedUsers = verifiedUsers;
        this.events = events;
    }

    @Override
    @Transactional("orgTransactionManager")
    public long submit(Organization.Type type, String name, String creditCode, long legalRepUserId) {
        if (verifiedUsers.findById(legalRepUserId).isEmpty()) {
            throw new IllegalStateException("法人代表未实名认证");
        }
        Organization org = orgs.save(new Organization(type, name, creditCode, legalRepUserId));
        events.publishEvent(new OrganizationSubmitted(org.getId(), legalRepUserId, Instant.now()));
        return org.getId();
    }

    @Override
    @Transactional("orgTransactionManager")
    public void approve(long orgId) {
        Organization org = orgs.findById(orgId).orElseThrow(() -> new IllegalArgumentException("组织不存在"));
        org.approve();
        orgs.save(org);
        events.publishEvent(new OrganizationApproved(orgId, Instant.now()));
    }

    @Override
    @Transactional("orgTransactionManager")
    public void reject(long orgId) {
        Organization org = orgs.findById(orgId).orElseThrow(() -> new IllegalArgumentException("组织不存在"));
        org.reject();
        orgs.save(org);
        events.publishEvent(new OrganizationRejected(orgId, Instant.now()));
    }

    @Override
    @Transactional(transactionManager = "orgTransactionManager", readOnly = true)
    public Optional<OrgView> findById(long orgId) {
        return orgs.findById(orgId).map(o -> new OrgView(
                o.getId(), o.getType(), o.getName(), o.getCreditCode(), o.getLegalRepUserId(), o.getStatus()));
    }
}
