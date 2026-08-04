package com.xbb.org.internal;

import com.xbb.org.api.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbb.identity.api.IdentityApi;
import com.xbb.identity.api.Role;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
class OrgService implements OrgApi {

    private final OrganizationRepository orgs;
    private final VerifiedUserRepository verifiedUsers;
    private final OrgOutboxRepository outbox;
    private final ObjectMapper json;
    private final IdentityApi identityApi;

    OrgService(OrganizationRepository orgs, VerifiedUserRepository verifiedUsers,
               OrgOutboxRepository outbox, ObjectMapper json,
                       IdentityApi identityApi) {
        this.orgs = orgs;
        this.verifiedUsers = verifiedUsers;
        this.outbox = outbox;
        this.json = json;
        this.identityApi = identityApi;
    }

    /**
     * 平台运维操作,要求 {@link Role#PLATFORM_OPS}。
     *
     * <p>这不是归属校验的替代品,而是它缺席时唯一说得通的东西:这几个动作的
     * "主人"是平台自己,不是某个用户。角色每次向身份域现查,不读 JWT 声明,
     * 这样收回权限立刻生效(理由同 OutboxOpsController)。
     */
    private void requirePlatformOps(long callerUserId) {
        if (!identityApi.hasRole(callerUserId, Role.PLATFORM_OPS)) {
            throw new AccessDeniedException("需要平台运维权限");
        }
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
    public void approve(long orgId, long callerUserId) {
        requirePlatformOps(callerUserId);
        Organization org = orgs.findById(orgId).orElseThrow(() -> new IllegalArgumentException("组织不存在"));
        org.approve();
        orgs.save(org);
        OrganizationApproved approved = new OrganizationApproved(orgId, org.getLegalRepUserId(), Instant.now());
        outbox.save(new OrgOutboxEvent(java.util.UUID.randomUUID().toString(),
                OrganizationApproved.class.getName(), serialize(approved)));
    }

    @Override
    @Transactional("orgTransactionManager")
    public void reject(long orgId, long callerUserId) {
        requirePlatformOps(callerUserId);
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
        return orgs.findById(orgId).map(OrgService::toView);
    }

    @Override
    @Transactional(transactionManager = "orgTransactionManager", readOnly = true)
    public List<OrgView> listByLegalRep(long legalRepUserId) {
        // 归属天然由查询条件保证:只查"法人代表是我"的行,不存在越权看到别人组织的可能。
        // 这比"查出来再过滤"稳 —— 过滤那一步漏写不会有任何症状。
        return orgs.findByLegalRepUserIdOrderByIdDesc(legalRepUserId).stream().map(OrgService::toView).toList();
    }

    @Override
    @Transactional(transactionManager = "orgTransactionManager", readOnly = true)
    public List<OrgView> listPending(long callerUserId) {
        requirePlatformOps(callerUserId);
        return orgs.findByStatusOrderByIdAsc(Organization.Status.PENDING).stream().map(OrgService::toView).toList();
    }

    private static OrgView toView(Organization o) {
        return new OrgView(o.getId(), o.getType(), o.getName(), o.getCreditCode(),
                o.getLegalRepUserId(), o.getStatus());
    }
}
