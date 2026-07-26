package com.xbb.org.internal;

import com.xbb.identity.api.UserVerified;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;


@Component
class IdentityEventListener {

    private final VerifiedUserRepository verifiedUsers;

    IdentityEventListener(VerifiedUserRepository verifiedUsers) {
        this.verifiedUsers = verifiedUsers;
    }

    // 原来用 @ApplicationModuleListener(=@Async+AFTER_COMMIT),审计报告发现两个问题:
    // 1) 副本写入是异步的,调用方紧接着提交入驻会因为副本还没落地被误判"未实名";
    // 2) 监听器失败时异常被异步异常处理器吞掉,identity 那边看起来什么事都没有,
    //    副本永久缺失也没人知道。改成同步(去掉 @Async,只留 AFTER_COMMIT):副本写入
    //    在原始 HTTP 请求返回前就完成,竞态窗口基本消失;写入失败会让原始请求收到
    //    错误而不是静默丢失——用可见的失败换掉不可见的数据不一致,这台机器上没有做
    //    真正的持久化重试(outbox),那需要额外的 spring-modulith-events-jdbc 基础设施,
    /**
     * `@EventListener` 而非 AFTER_COMMIT:该事件由发布方的 outbox 中继投递。
     * AFTER_COMMIT 的监听器要等中继事务提交后才跑,那时 outbox 行已是 PUBLISHED,
     * 这里再抛异常事件就永久丢了(理由详见 AbstractOutboxRelay)。
     */
    @EventListener
    @Transactional(transactionManager = "orgTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(UserVerified event) {
        verifiedUsers.save(new VerifiedUser(event.userId(), event.realName(), event.occurredAt()));
    }
}
