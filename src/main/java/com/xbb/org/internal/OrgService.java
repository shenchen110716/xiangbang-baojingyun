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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OrgService.class);

    private final OrganizationRepository orgs;
    private final StationMasterChangeRepository changes;
    private final VerifiedUserRepository verifiedUsers;
    private final OrgOutboxRepository outbox;
    private final ObjectMapper json;
    private final IdentityApi identityApi;

    OrgService(OrganizationRepository orgs, StationMasterChangeRepository changes,
               VerifiedUserRepository verifiedUsers,
               OrgOutboxRepository outbox, ObjectMapper json,
                       IdentityApi identityApi) {
        this.orgs = orgs;
        this.changes = changes;
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
    public long submit(com.xbb.org.api.OrgType type, String name, String creditCode, long legalRepUserId) {
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

    /**
     * 平台直接设立服务站。建出来就是**已审核、且还没有站长**。
     *
     * <p>不复用 submit + approve:那条路要求先有一个已实名的法人代表,
     * 而这里的顺序恰恰相反 —— 平台先规划点位,再决定派谁去管。
     */
    @Override
    @Transactional("orgTransactionManager")
    public long createStation(String name, String creditCode, long callerUserId) {
        requirePlatformOps(callerUserId);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("请填写服务站名称");
        }
        if (creditCode == null || creditCode.isBlank()) {
            // 服务站是要收佣金的经营主体,没有信用代码就开不了对公账户、走不了代发
            throw new IllegalArgumentException("请填写统一社会信用代码");
        }
        Organization station = orgs.save(Organization.platformStation(name.trim(), creditCode.trim()));
        // 直接发"已通过"事件:下游(经纪人域的服务站副本、结算域的组织副本)
        // 只认这一个事件,不该为平台建站再造一条并行的通知路径
        // legalRepUserId 传 0 表示"还没有站长" —— 下游副本据此知道这个站暂时无人管理
        OrganizationApproved approved = new OrganizationApproved(
                station.getId(), 0L, station.getType(), Instant.now());
        outbox.save(new OrgOutboxEvent(java.util.UUID.randomUUID().toString(),
                OrganizationApproved.class.getName(), serialize(approved)));
        log.info("平台设立服务站:org={} 名称={} 暂无站长", station.getId(), name);
        return station.getId();
    }

    /**
     * 指派或更换站长。**先留痕再变更**(老系统 M10 §4.3)。
     *
     * <p>换站长会改变谁能设分成比例、谁能签联合协议 —— 那都是动钱的权力。
     */
    @Override
    @Transactional("orgTransactionManager")
    public void assignStationMaster(long orgId, Long newMasterUserId, String reason, long callerUserId) {
        requirePlatformOps(callerUserId);
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("请填写变更原因");
        }
        Organization station = orgs.findById(orgId)
                .orElseThrow(() -> new IllegalArgumentException("服务站不存在"));
        // **类型先判。**倒过来的话,对着一个工厂调这个方法会得到"新站长未实名认证" ——
        // 拦是拦住了,但没说真正的原因是这条路根本不适用于工厂
        if (station.getType() != com.xbb.org.api.OrgType.SERVICE_STATION) {
            throw new IllegalStateException("只有服务站可以更换负责人");
        }
        if (newMasterUserId != null && verifiedUsers.findById(newMasterUserId).isEmpty()) {
            // 站长要签联合协议、要被记进佣金归属,没实名的话这些都无从追溯
            throw new IllegalStateException("新站长未实名认证");
        }
        Long old = station.getLegalRepUserId();
        if (java.util.Objects.equals(old, newMasterUserId)) {
            throw new IllegalStateException("新站长和当前站长是同一个人");
        }
        // 先留痕再变更:反过来的话,变更成功而留痕失败时,这次变更就没人知道了
        changes.save(new StationMasterChange(orgId, old, newMasterUserId, callerUserId, reason.trim()));
        station.assignMaster(newMasterUserId);
        orgs.save(station);

        // 站长变了,经纪人域的服务站副本要跟着变 —— 它是联合协议授权的依据
        OrganizationApproved approved = new OrganizationApproved(
                orgId, newMasterUserId == null ? 0L : newMasterUserId, station.getType(), Instant.now());
        outbox.save(new OrgOutboxEvent(java.util.UUID.randomUUID().toString(),
                OrganizationApproved.class.getName(), serialize(approved)));
        log.info("站长变更:org={} {} → {} 操作人={} 原因={}", orgId, old, newMasterUserId, callerUserId, reason);
    }

    @Override
    @Transactional(transactionManager = "orgTransactionManager", readOnly = true)
    public List<MasterChangeView> stationMasterChanges(long orgId, long callerUserId) {
        requirePlatformOps(callerUserId);
        return changes.findByOrgIdOrderByIdDesc(orgId).stream()
                .map(c -> new MasterChangeView(c.getId(), c.getOrgId(), c.getOldUserId(),
                        c.getNewUserId(), c.getChangedBy(), c.getReason(), c.getChangedAt()))
                .toList();
    }

    @Override
    @Transactional("orgTransactionManager")
    public void approve(long orgId, long callerUserId) {
        requirePlatformOps(callerUserId);
        Organization org = orgs.findById(orgId).orElseThrow(() -> new IllegalArgumentException("组织不存在"));
        org.approve();
        orgs.save(org);
        // 服务站可能还没指派站长(平台先建点位)。事件里传 0 表示"暂时无人管理" ——
        // 直接拆箱会 NPE,而那是**编译期看不出来、上线才炸**的那种
        Long rep = org.getLegalRepUserId();
        OrganizationApproved approved = new OrganizationApproved(
                orgId, rep == null ? 0L : rep, org.getType(), Instant.now());
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
    public Optional<OrgView> findById(long orgId, long callerUserId) {
        return orgs.findById(orgId)
                // 法人代表本人或平台运维。信用代码 + 法人是把人和企业对应起来的东西,
                // 招聘信息公开不等于这些也公开
                // 用 Objects.equals:无站长的服务站 getLegalRepUserId() 是 null,
                // `== callerUserId` 会拆箱 NPE
                .filter(o -> java.util.Objects.equals(o.getLegalRepUserId(), callerUserId)
                        || identityApi.hasRole(callerUserId, com.xbb.identity.api.Role.PLATFORM_OPS))
                .map(OrgService::toView);
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
