package com.xbb.org.internal;

import com.xbb.org.api.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
class OrgService implements OrgApi {

    private final OrganizationRepository orgs;
    private final VerifiedUserRepository verifiedUsers;
    private final OrgOutboxRepository outbox;
    private final ObjectMapper json;

    OrgService(OrganizationRepository orgs, VerifiedUserRepository verifiedUsers,
               OrgOutboxRepository outbox, ObjectMapper json) {
        this.orgs = orgs;
        this.verifiedUsers = verifiedUsers;
        this.outbox = outbox;
        this.json = json;
    }

    private String serialize(Object event) {
        try {
            return json.writeValueAsString(event);
        } catch (Exception e) {
            // 序列化不了就别让这步业务成功——事件发不出去,下游永远补不回来
            throw new IllegalStateException("事件无法序列化: " + event, e);
        }
    }

    @Override
    @Transactional("orgTransactionManager")
    public long submit(Organization.Type type, String name, String creditCode, long legalRepUserId) {
        if (verifiedUsers.findById(legalRepUserId).isEmpty()) {
            throw new IllegalStateException("法人代表未实名认证");
        }
        Organization org = orgs.save(new Organization(type, name, creditCode, legalRepUserId));
        // 同 identity:暂无订阅者,但不在一个类里并存两套发事件的机制。
        OrganizationSubmitted submitted = new OrganizationSubmitted(org.getId(), legalRepUserId, Instant.now());
        outbox.save(new OrgOutboxEvent(java.util.UUID.randomUUID().toString(),
                OrganizationSubmitted.class.getName(), serialize(submitted)));
        return org.getId();
    }

    @Override
    @Transactional("orgTransactionManager")
    public void approve(long orgId) {
        Organization org = orgs.findById(orgId).orElseThrow(() -> new IllegalArgumentException("组织不存在"));
        org.approve();
        orgs.save(org);
        OrganizationApproved approved = new OrganizationApproved(orgId, org.getLegalRepUserId(), Instant.now());
        outbox.save(new OrgOutboxEvent(java.util.UUID.randomUUID().toString(),
                OrganizationApproved.class.getName(), serialize(approved)));
    }

    @Override
    @Transactional("orgTransactionManager")
    public void reject(long orgId) {
        Organization org = orgs.findById(orgId).orElseThrow(() -> new IllegalArgumentException("组织不存在"));
        org.reject();
        orgs.save(org);
        OrganizationRejected rejected = new OrganizationRejected(orgId, Instant.now());
        outbox.save(new OrgOutboxEvent(java.util.UUID.randomUUID().toString(),
                OrganizationRejected.class.getName(), serialize(rejected)));
    }

    @Override
    @Transactional(transactionManager = "orgTransactionManager", readOnly = true)
    public Optional<OrgView> findById(long orgId) {
        return orgs.findById(orgId).map(o -> new OrgView(
                o.getId(), o.getType(), o.getName(), o.getCreditCode(), o.getLegalRepUserId(), o.getStatus()));
    }
}
