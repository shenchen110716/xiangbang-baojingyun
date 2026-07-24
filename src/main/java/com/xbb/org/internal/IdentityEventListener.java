package com.xbb.org.internal;

import com.xbb.identity.api.UserVerified;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class IdentityEventListener {

    private final VerifiedUserRepository verifiedUsers;

    IdentityEventListener(VerifiedUserRepository verifiedUsers) {
        this.verifiedUsers = verifiedUsers;
    }

    // @ApplicationModuleListener = @Async + @TransactionalEventListener(AFTER_COMMIT):
    // identity 域的事务已经提交完了,这里必须开一个全新事务(REQUIRES_NEW),而且要指定
    // orgTransactionManager —— 两个域各自的 TransactionManager 都在容器里,没有 @Primary。
    @ApplicationModuleListener
    @Transactional(transactionManager = "orgTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(UserVerified event) {
        verifiedUsers.save(new VerifiedUser(event.userId(), event.realName(), event.occurredAt()));
    }
}
