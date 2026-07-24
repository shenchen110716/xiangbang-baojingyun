package com.xbb.broker.internal;

import com.xbb.broker.api.BrokerApi;
import com.xbb.broker.api.BrokerRegistered;
import com.xbb.broker.api.WorkerBound;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
class BrokerService implements BrokerApi {

    private final BrokerRepository brokers;
    private final InvitationRepository invitations;
    private final BrokerVerifiedUserRepository verifiedUsers;
    private final ApplicationEventPublisher events;

    BrokerService(BrokerRepository brokers, InvitationRepository invitations,
                  BrokerVerifiedUserRepository verifiedUsers, ApplicationEventPublisher events) {
        this.brokers = brokers;
        this.invitations = invitations;
        this.verifiedUsers = verifiedUsers;
        this.events = events;
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
        events.publishEvent(new BrokerRegistered(userId, Instant.now()));
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
        events.publishEvent(new WorkerBound(invitation.getId(), brokerUserId, workerUserId, Instant.now()));
        return invitation.getId();
    }

    @Override
    @Transactional(transactionManager = "brokerTransactionManager", readOnly = true)
    public Optional<BrokerView> findBroker(long userId) {
        return Optional.of(new BrokerView(userId, brokers.existsById(userId)));
    }
}
