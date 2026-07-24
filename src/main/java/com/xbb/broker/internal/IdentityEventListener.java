package com.xbb.broker.internal;

import com.xbb.identity.api.UserVerified;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import static org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT;

// 显式命名:其它域也有同名类 IdentityEventListener,默认 bean 名会撞车
@Component("brokerIdentityEventListener")
class IdentityEventListener {

    private final BrokerVerifiedUserRepository verifiedUsers;

    IdentityEventListener(BrokerVerifiedUserRepository verifiedUsers) {
        this.verifiedUsers = verifiedUsers;
    }

    // 同步(非 @Async)AFTER_COMMIT,理由见 org.internal.IdentityEventListener 的注释(审计修复)。
    @TransactionalEventListener(phase = AFTER_COMMIT)
    @Transactional(transactionManager = "brokerTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(UserVerified event) {
        verifiedUsers.save(new VerifiedUser(event.userId(), event.occurredAt()));
    }
}
