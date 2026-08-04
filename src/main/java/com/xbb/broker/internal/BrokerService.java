package com.xbb.broker.internal;

import com.xbb.broker.api.BrokerApi;
import com.xbb.broker.api.BrokerRegistered;
import com.xbb.broker.api.CommissionPaid;
import com.xbb.broker.api.WorkerBound;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbb.identity.api.IdentityApi;
import com.xbb.identity.api.Role;
import org.springframework.security.access.AccessDeniedException;
import com.xbb.fund.api.AccountType;
import com.xbb.fund.api.FundApi;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
class BrokerService implements BrokerApi {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BrokerService.class);

    private final BrokerRepository brokers;
    private final StationRepository stations;
    private final BrokerChangeLogRepository changeLogs;
    private final com.xbb.ops.api.OpsApi opsApi;
    private final InvitationRepository invitations;
    private final CommissionRepository commissions;
    private final BrokerVerifiedUserRepository verifiedUsers;
    private final BrokerOutboxRepository outbox;
    private final ObjectMapper json;
    private final IdentityApi identityApi;
    private final FundApi fundApi;

    BrokerService(BrokerRepository brokers, InvitationRepository invitations, CommissionRepository commissions,
                  BrokerVerifiedUserRepository verifiedUsers,
                     BrokerOutboxRepository outbox, ObjectMapper json,
                       IdentityApi identityApi, FundApi fundApi,
                  StationRepository stations, BrokerChangeLogRepository changeLogs,
                  com.xbb.ops.api.OpsApi opsApi) {
        this.stations = stations;
        this.changeLogs = changeLogs;
        this.opsApi = opsApi;
        this.brokers = brokers;
        this.invitations = invitations;
        this.commissions = commissions;
        this.verifiedUsers = verifiedUsers;
        this.outbox = outbox;
        this.json = json;
        this.identityApi = identityApi;
        this.fundApi = fundApi;
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
    @Transactional("brokerTransactionManager")
    public void registerBroker(long userId) {
        if (verifiedUsers.findById(userId).isEmpty()) {
            throw new IllegalStateException("需要完成实名认证才能注册经纪人");
        }
        if (brokers.existsById(userId)) {
            throw new IllegalStateException("已经是经纪人,不可重复注册");
        }
        brokers.save(new Broker(userId));
        BrokerRegistered registered = new BrokerRegistered(userId, Instant.now());
        outbox.save(new BrokerOutboxEvent(java.util.UUID.randomUUID().toString(),
                BrokerRegistered.class.getName(), serialize(registered)));
    }

    @Override
    @Transactional("brokerTransactionManager")
    public long bindWorker(long brokerUserId, long workerUserId) {
        if (!brokers.existsById(brokerUserId)) {
            throw new IllegalStateException("调用者不是经纪人");
        }
        if (verifiedUsers.findById(workerUserId).isEmpty()) {
            throw new IllegalStateException("工人需要完成实名认证才能被绑定");
        }
        // 唯一约束(worker_user_id UNIQUE)在数据库层兜底并发抢绑;
        // 这里先应用层查一次给出更友好的错误信息,DataIntegrityViolationException
        // 交给 com.xbb.web.GlobalExceptionHandler 兜底处理并发窗口内的漏网之鱼。
        if (invitations.findByWorkerUserId(workerUserId).isPresent()) {
            throw new IllegalStateException("该工人已经绑定过经纪人");
        }
        Invitation invitation = invitations.save(new Invitation(brokerUserId, workerUserId));
        WorkerBound bound = new WorkerBound(invitation.getId(), brokerUserId, workerUserId, Instant.now());
        outbox.save(new BrokerOutboxEvent(java.util.UUID.randomUUID().toString(),
                WorkerBound.class.getName(), serialize(bound)));
        return invitation.getId();
    }

    @Override
    @Transactional("brokerTransactionManager")
    public void payCommission(long commissionId, long callerUserId) {
        requirePlatformOps(callerUserId);
        Commission commission = commissions.findById(commissionId)
                .orElseThrow(() -> new IllegalArgumentException("佣金记录不存在"));
        // **钱必须真的从平台账户出去。** 之前这里只把记录标成已付、发个事件,
        // 没有任何账户被扣:平台对外承诺付出的比账上实际出的多,账实不符;
        // 而 CommissionPaid 还没有任何订阅者,也就没人会去补这一步。
        // §4.1 决策#1:资金域是唯一动钱者,所以走它的接口,不自己记账。
        fundApi.spendFromAccount(AccountType.PLATFORM_REVENUE, commission.getAmountCents(),
                "经纪人佣金 commission#" + commissionId, "commission-" + commissionId);

        commission.pay();
        commissions.save(commission);
        CommissionPaid paid = new CommissionPaid(
                commissionId, commission.getBrokerUserId(), commission.getAmountCents(), Instant.now());
        outbox.save(new BrokerOutboxEvent(java.util.UUID.randomUUID().toString(),
                CommissionPaid.class.getName(), serialize(paid)));
    }

    @Override
    @Transactional(transactionManager = "brokerTransactionManager", readOnly = true)
    public Optional<BrokerView> findBroker(long userId) {
        return Optional.of(new BrokerView(userId, brokers.existsById(userId)));
    }

    @Override
    @Transactional(transactionManager = "brokerTransactionManager", readOnly = true)
    public Optional<CommissionView> findCommission(long commissionId) {
        return commissions.findById(commissionId).map(c -> new CommissionView(
                c.getId(), c.getBrokerUserId(), c.getWorkerUserId(), c.getSettlementId(),
                c.getAmountCents(), c.getStatus()));
    }

    // ─────────────── 服务站与业务员网络 ───────────────

    @Override
    @Transactional(transactionManager = "brokerTransactionManager", readOnly = true)
    public List<StationView> listStations(long callerUserId) {
        requirePlatformOps(callerUserId);
        int platformDefault = (int) opsApi.settingInt(
                com.xbb.ops.api.SettingKeys.COMMISSION_STATION_PERCENT, 50);
        return stations.findAllByOrderByOrgIdAsc().stream()
                .map(st -> new StationView(st.getOrgId(), st.getName(), st.getLegalRepUserId(),
                        st.getStationPercent(),
                        st.getStationPercent() == null ? platformDefault : st.getStationPercent(),
                        st.getApprovedAt(),
                        brokers.findByStationOrgIdOrderByUserIdAsc(st.getOrgId()).size()))
                .toList();
    }

    @Override
    @Transactional("brokerTransactionManager")
    public void setStationPercent(long stationOrgId, Integer percent, String reason, long callerUserId) {
        requirePlatformOps(callerUserId);
        requireReason(reason);
        Station st = stations.findById(stationOrgId)
                .orElseThrow(() -> new IllegalArgumentException("服务站不存在"));
        st.setStationPercent(percent);
        stations.save(st);
        log.warn("服务站佣金比例变更: station={} → {} 操作人={} 理由={}",
                stationOrgId, percent == null ? "跟随平台默认" : percent + "%", callerUserId, reason);
    }

    @Override
    @Transactional(transactionManager = "brokerTransactionManager", readOnly = true)
    public List<BrokerNodeView> listBrokers(Long stationOrgId, long callerUserId) {
        requirePlatformOps(callerUserId);
        List<Broker> rows = stationOrgId == null
                ? brokers.findAll()
                : brokers.findByStationOrgIdOrderByUserIdAsc(stationOrgId);
        return rows.stream().map(b -> new BrokerNodeView(b.getUserId(), b.getStationOrgId(),
                        b.getParentUserId(), b.getLastActiveAt(), b.getStatus().name(),
                        brokers.findByParentUserId(b.getUserId()).size()))
                .toList();
    }

    @Override
    @Transactional("brokerTransactionManager")
    public void assignStation(long brokerUserId, Long stationOrgId, String reason, long callerUserId) {
        requirePlatformOps(callerUserId);
        requireReason(reason);
        if (stationOrgId != null && stations.findById(stationOrgId).isEmpty()) {
            throw new IllegalArgumentException("服务站不存在");
        }
        Broker b = requireBroker(brokerUserId);
        Long old = b.getStationOrgId();
        b.assignStation(stationOrgId);
        brokers.save(b);
        logChange(brokerUserId, BrokerChangeLog.ChangeType.STATION, old, stationOrgId, callerUserId, reason);
    }

    @Override
    @Transactional("brokerTransactionManager")
    public void assignParent(long brokerUserId, Long parentUserId, String reason, long callerUserId) {
        requirePlatformOps(callerUserId);
        requireReason(reason);
        Broker b = requireBroker(brokerUserId);
        if (parentUserId != null) {
            requireBroker(parentUserId);
            requireNoCycle(brokerUserId, parentUserId);
        }
        Long old = b.getParentUserId();
        b.assignParent(parentUserId);
        brokers.save(b);
        logChange(brokerUserId, BrokerChangeLog.ChangeType.PARENT, old, parentUserId, callerUserId, reason);
    }

    /**
     * 从候选上级往根走,路上撞见自己就是成环。
     *
     * <p>加深度上限是因为**数据里已经有环的话这个循环本身会卡死** ——
     * 用来防环的代码不能假设数据无环。
     */
    private void requireNoCycle(long brokerUserId, long candidateParentId) {
        Long cursor = candidateParentId;
        for (int depth = 0; cursor != null && depth < 100; depth++) {
            if (cursor == brokerUserId) {
                throw new IllegalArgumentException("不能把业务员挂到自己的下级名下,会形成闭环");
            }
            cursor = brokers.findById(cursor).map(Broker::getParentUserId).orElse(null);
        }
        if (cursor != null) {
            throw new IllegalStateException("上级链条超过 100 层,疑似已存在闭环,请先人工核查");
        }
    }

    @Override
    @Transactional("brokerTransactionManager")
    public void touchBroker(long brokerUserId) {
        brokers.findById(brokerUserId).ifPresent(b -> { b.touch(); brokers.save(b); });
    }

    @Override
    @Transactional(transactionManager = "brokerTransactionManager", readOnly = true)
    public List<BrokerChangeView> brokerChanges(Long brokerUserId, long callerUserId) {
        requirePlatformOps(callerUserId);
        List<BrokerChangeLog> rows = brokerUserId == null
                ? changeLogs.findTop100ByOrderByChangedAtDesc()
                : changeLogs.findByBrokerUserIdOrderByChangedAtDesc(brokerUserId);
        return rows.stream().map(c -> new BrokerChangeView(c.getId(), c.getBrokerUserId(),
                c.getChangeType().name(), c.getOldValue(), c.getNewValue(),
                c.getChangedBy(), c.getChangedAt(), c.getReason())).toList();
    }

    private Broker requireBroker(long userId) {
        return brokers.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("业务员不存在: " + userId));
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("必须填写变更理由");
        }
    }

    void logChange(long brokerUserId, BrokerChangeLog.ChangeType type,
                   Object oldValue, Object newValue, Long operator, String reason) {
        changeLogs.save(new BrokerChangeLog(brokerUserId, type,
                oldValue == null ? null : String.valueOf(oldValue),
                newValue == null ? null : String.valueOf(newValue),
                operator, reason));
    }
}
