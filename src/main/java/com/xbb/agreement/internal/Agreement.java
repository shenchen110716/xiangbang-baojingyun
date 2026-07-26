package com.xbb.agreement.internal;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "agreement", schema = "agreement")
public class Agreement {

    public enum Status { PENDING, SIGNED }

    /**
     * 身份因子(§6.2:"电子签的身份因子(人脸/短信)不可省——
     * 这是法律效力要件,不是体验问题")。
     */
    public enum IdentityFactor { FACE, SMS }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false, unique = true)
    private long applicationId;

    @Column(name = "worker_user_id", nullable = false)
    private long workerUserId;

    @Column(name = "org_id", nullable = false)
    private long orgId;

    @Column(nullable = false)
    private String content;

    /** 存证哈希,签署后也不变——纠纷举证靠它证明正文没被篡改。 */
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "identity_factor", length = 20)
    private IdentityFactor identityFactor;

    @Column(name = "provider_ref", length = 100)
    private String providerRef;

    @Column(name = "signed_at")
    private Instant signedAt;

    /** 生成时所用的模板版本。模板以后会改,举证要能翻出**当时那一版**(§6.2)。 */
    @Column(name = "template_key", length = 50)
    private String templateKey;

    @Column(name = "template_version")
    private Integer templateVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Version
    private long version;

    protected Agreement() { }

    public Agreement(long applicationId, long workerUserId, long orgId, String content, String contentHash,
                      String templateKey, Integer templateVersion) {
        this.templateKey = templateKey;
        this.templateVersion = templateVersion;
        this.applicationId = applicationId;
        this.workerUserId = workerUserId;
        this.orgId = orgId;
        this.content = content;
        this.contentHash = contentHash;
    }

    public void sign(IdentityFactor identityFactor, String providerRef) {
        if (status != Status.PENDING) throw new IllegalStateException("协议已签署,不能重复签署");
        this.status = Status.SIGNED;
        this.identityFactor = identityFactor;
        this.providerRef = providerRef;
        this.signedAt = Instant.now();
    }

    public String getTemplateKey() { return templateKey; }
    public Integer getTemplateVersion() { return templateVersion; }

    public Long getId() { return id; }
    public long getApplicationId() { return applicationId; }
    public long getWorkerUserId() { return workerUserId; }
    public long getOrgId() { return orgId; }
    public String getContent() { return content; }
    public String getContentHash() { return contentHash; }
    public Status getStatus() { return status; }
    public IdentityFactor getIdentityFactor() { return identityFactor; }
    public String getProviderRef() { return providerRef; }
    public Instant getSignedAt() { return signedAt; }
}
