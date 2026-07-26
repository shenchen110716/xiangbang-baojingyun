package com.xbb.engagement.internal;

import com.xbb.agreement.api.AgreementSigned;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import static org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT;

/** 协议签署后放行"确认完成"(§6.2:协议签署是到岗的前置门禁)。 */
@Component("engagementAgreementEventListener")
class AgreementEventListener {

    private final SignedAgreementRepository signedAgreements;

    AgreementEventListener(SignedAgreementRepository signedAgreements) {
        this.signedAgreements = signedAgreements;
    }

    // 同步(非 @Async)AFTER_COMMIT,理由见 org.internal.IdentityEventListener 的注释(审计修复)。
    @TransactionalEventListener(phase = AFTER_COMMIT)
    @Transactional(transactionManager = "engagementTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(AgreementSigned event) {
        signedAgreements.save(new SignedAgreement(
                event.applicationId(), event.contentHash(), event.occurredAt()));
    }
}
