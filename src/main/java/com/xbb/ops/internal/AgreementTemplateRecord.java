package com.xbb.ops.internal;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 协议模板的一个版本(§6.2)。
 *
 * <p>类名带 Record 后缀是为了不和协议域里那个负责渲染的 AgreementTemplate 混淆:
 * 那个是"怎么渲染",这个是"渲染用哪一版文本"。
 */
@Entity
@Table(name = "agreement_template", schema = "ops")
public class AgreementTemplateRecord {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "template_key", nullable = false, length = 50)
    private String templateKey;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false)
    private String body;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected AgreementTemplateRecord() { }

    public AgreementTemplateRecord(String templateKey, int version, String body) {
        this.templateKey = templateKey;
        this.version = version;
        this.body = body;
        this.active = true;
    }

    /** 下架。正文不动——已签协议要能按版本号翻出**当时**的文本。 */
    public void retire() { this.active = false; }

    public Long getId() { return id; }
    public String getTemplateKey() { return templateKey; }
    public int getVersion() { return version; }
    public String getBody() { return body; }
    public boolean isActive() { return active; }
}
