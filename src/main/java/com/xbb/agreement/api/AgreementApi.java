package com.xbb.agreement.api;

import com.xbb.agreement.internal.Agreement;

import java.util.Optional;

public interface AgreementApi {

    record AgreementView(long id, long applicationId, long workerUserId, long orgId,
                          String content, String contentHash, Agreement.Status status,
                          String providerRef, String templateKey, Integer templateVersion) { }

    /**
     * 生成待签协议。由**履约域**在录用后调用——协议是履约流程的一环,
     * 生成时机由履约流程主导。
     *
     * <p>方向是单向的 engagement → agreement:协议域不反向订阅履约事件。
     * 两边互相订阅会形成真实的循环依赖(ModularityTests 会直接拦下,
     * 同 Plan6 的 settlement↔fund)。
     */
    void generate(long applicationId, long jobId, long workerUserId, long orgId, long wageCents);

    /**
     * 签署协议。identityFactor 必传且必须合法——§6.2:电子签的身份因子
     * (人脸/短信)不可省,这是法律效力要件,不是体验问题。
     */
    void sign(long applicationId, long signerUserId, String identityFactor);

    Optional<AgreementView> findByApplicationId(long applicationId);
}
