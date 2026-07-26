package com.xbb.engagement.internal;

import com.xbb.agreement.api.AgreementSigned;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;


/** 协议签署后放行"确认完成"(§6.2:协议签署是到岗的前置门禁)。 */
@Component("engagementAgreementEventListener")
class AgreementEventListener {

    private final SignedAgreementRepository signedAgreements;

    AgreementEventListener(SignedAgreementRepository signedAgreements) {
        this.signedAgreements = signedAgreements;
    }

    /**
     * `@EventListener` 而非 AFTER_COMMIT:所有跨域事件都由发布方的 outbox 中继投递。
     * AFTER_COMMIT 的监听器要等中继事务提交后才跑,那时 outbox 行已是 PUBLISHED,
     * 这里再抛异常事件就永久丢了(理由详见 AbstractOutboxRelay)。
     */
    @EventListener
    @Transactional(transactionManager = "engagementTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(AgreementSigned event) {
        signedAgreements.save(new SignedAgreement(
                event.applicationId(), event.contentHash(), event.occurredAt()));
    }
}
