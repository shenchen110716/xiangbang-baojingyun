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
import java.util.Optional;

@Service
class BrokerService implements BrokerApi {

    private final BrokerRepository brokers;
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
                       IdentityApi identityApi, FundApi fundApi) {
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
}
