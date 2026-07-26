package com.xbb.engagement.internal;

import com.xbb.agreement.api.AgreementApi;
import com.xbb.engagement.api.ApplicationAccepted;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import static org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT;

/**
 * 录用后生成待签协议(§6.2:录用 → 生成协议 → 工人电子签)。
 *
 * <p>订阅的是**本域自己**发的 ApplicationAccepted,再调用协议域的 API——
 * 这样依赖方向只有 engagement → agreement 一条。反过来让协议域订阅履约事件
 * 会和这里的"履约订阅协议签署"形成循环依赖,ModularityTests 直接拦下
 * (同 Plan6 settlement↔fund 的教训)。
 *
 * <p>放在 AFTER_COMMIT:录用事务万一回滚,不该留下一份孤儿协议。
 */
@Component
class AgreementGenerationListener {

    private final AgreementApi agreementApi;

    AgreementGenerationListener(AgreementApi agreementApi) {
        this.agreementApi = agreementApi;
    }

    @TransactionalEventListener(phase = AFTER_COMMIT)
    void on(ApplicationAccepted event) {
        agreementApi.generate(event.applicationId(), event.jobId(),
                event.applicantUserId(), event.orgId(), event.wageCents());
    }
}
