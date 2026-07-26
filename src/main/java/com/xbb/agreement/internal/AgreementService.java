package com.xbb.agreement.internal;

import com.xbb.agreement.api.AgreementApi;
import com.xbb.agreement.api.AgreementGenerated;
import com.xbb.agreement.api.AgreementSigned;
import com.xbb.ops.api.OpsApi;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
class AgreementService implements AgreementApi {

    private final AgreementRepository agreements;
    private final SigningProvider signingProvider;
    private final OpsApi ops;
    private final ApplicationEventPublisher events;

    AgreementService(AgreementRepository agreements, SigningProvider signingProvider,
                      OpsApi ops, ApplicationEventPublisher events) {
        this.agreements = agreements;
        this.signingProvider = signingProvider;
        this.ops = ops;
        this.events = events;
    }

    @Override
    @Transactional("agreementTransactionManager")
    public void generate(long applicationId, long jobId, long workerUserId, long orgId, long wageCents) {
        // 幂等:重复录用事件/重试不应生成第二份协议
        if (agreements.findByApplicationId(applicationId).isPresent()) return;

        // 没有生效模板就不生成:宁可这一单卡住让运营去发布模板,也不能凭空拿个空文本去签。
        OpsApi.AgreementTemplateView template = ops.activeTemplate(OpsApi.LABOR_AGREEMENT)
                .orElseThrow(() -> new IllegalStateException("没有生效的劳务协议模板,无法生成协议"));

        String content = AgreementTemplate.render(
                template.body(), applicationId, jobId, workerUserId, orgId, wageCents);
        String hash = AgreementTemplate.hash(content);
        Agreement agreement = agreements.save(new Agreement(applicationId, workerUserId, orgId, content, hash,
                template.templateKey(), template.version()));
        events.publishEvent(new AgreementGenerated(
                agreement.getId(), applicationId, workerUserId, orgId, hash, Instant.now()));
    }

    @Override
    @Transactional("agreementTransactionManager")
    public void sign(long applicationId, long signerUserId, String identityFactor) {
        Agreement agreement = agreements.findByApplicationId(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("协议不存在"));

        // 只有协议约定的乙方本人能签。别人替签在电子签场景里是致命问题。
        if (agreement.getWorkerUserId() != signerUserId) {
            throw new IllegalStateException("只有本人可以签署自己的协议");
        }

        Agreement.IdentityFactor factor = parseFactor(identityFactor);

        SigningProvider.Receipt receipt = signingProvider.sign(
                agreement.getContent(), agreement.getContentHash(), signerUserId, factor);
        agreement.sign(factor, receipt.providerRef());
        agreements.save(agreement);

        events.publishEvent(new AgreementSigned(
                agreement.getId(), applicationId, agreement.getWorkerUserId(),
                agreement.getOrgId(), agreement.getContentHash(), Instant.now()));
    }

    /**
     * §6.2:"电子签的身份因子(人脸/短信)不可省——这是法律效力要件,不是体验问题。"
     * 所以不提供"不传就默认某个因子"的宽松路径,缺失或非法一律拒绝。
     */
    private static Agreement.IdentityFactor parseFactor(String identityFactor) {
        if (identityFactor == null || identityFactor.isBlank()) {
            throw new IllegalArgumentException("必须提供身份因子(人脸或短信)才能签署,这是法律效力要件");
        }
        try {
            return Agreement.IdentityFactor.valueOf(identityFactor.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的身份因子: " + identityFactor);
        }
    }

    @Override
    @Transactional(transactionManager = "agreementTransactionManager", readOnly = true)
    public Optional<AgreementView> findByApplicationId(long applicationId) {
        return agreements.findByApplicationId(applicationId).map(a -> new AgreementView(
                a.getId(), a.getApplicationId(), a.getWorkerUserId(), a.getOrgId(),
                a.getContent(), a.getContentHash(), a.getStatus(), a.getProviderRef(),
                a.getTemplateKey(), a.getTemplateVersion()));
    }
}
