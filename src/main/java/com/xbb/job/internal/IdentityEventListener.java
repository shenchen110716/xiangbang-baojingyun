package com.xbb.job.internal;

import com.xbb.identity.api.UserVerified;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 显式命名:org 域也有个同名类 IdentityEventListener,默认 bean 名会撞车
@Component("jobIdentityEventListener")
class IdentityEventListener {

    private final JobVerifiedUserRepository verifiedUsers;

    IdentityEventListener(JobVerifiedUserRepository verifiedUsers) {
        this.verifiedUsers = verifiedUsers;
    }

    @ApplicationModuleListener
    @Transactional(transactionManager = "jobTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(UserVerified event) {
        verifiedUsers.save(new VerifiedUser(event.userId(), event.occurredAt()));
    }
}
