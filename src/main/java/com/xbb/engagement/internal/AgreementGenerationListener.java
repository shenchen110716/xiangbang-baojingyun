package com.xbb.engagement.internal;

import com.xbb.agreement.api.AgreementApi;
import com.xbb.engagement.api.ApplicationAccepted;
import org.springframework.stereotype.Component;
import org.springframework.context.event.EventListener;

/**
 * 录用后生成待签协议(§6.2:录用 → 生成协议 → 工人电子签)。
 *
 * <p>订阅的是**本域自己**发的 ApplicationAccepted,再调用协议域的 API——
 * 这样依赖方向只有 engagement → agreement 一条。反过来让协议域订阅履约事件
 * 会和这里的"履约订阅协议签署"形成循环依赖,ModularityTests 直接拦下
 * (同 Plan6 settlement↔fund 的教训)。
 *
 * <p>"录用事务回滚就不该留下孤儿协议"这个保证,现在由 outbox 提供:
 * ApplicationAccepted 与录用状态同事务落库,回滚了就根本没有这行事件。
 * 所以这里用 {@code @EventListener} 内联执行——中继在自己的事务里投递,
 * 用 AFTER_COMMIT 反而会等到中继事务提交之后才跑,那时 outbox 行已被标成
 * PUBLISHED,这里一抛异常协议就永远不会生成了。
 */
@Component
class AgreementGenerationListener {

    private final AgreementApi agreementApi;

    AgreementGenerationListener(AgreementApi agreementApi) {
        this.agreementApi = agreementApi;
    }

    @EventListener
    void on(ApplicationAccepted event) {
        agreementApi.generate(event.applicationId(), event.jobId(),
                event.applicantUserId(), event.orgId(), event.wageCents());
    }
}
