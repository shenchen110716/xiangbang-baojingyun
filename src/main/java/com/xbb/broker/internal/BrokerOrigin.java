package com.xbb.broker.internal;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 这个人凭什么是业务员。
 *
 * <p>自动升级那条尤其要记:**是系统替他做的决定**,
 * 事后问起来"我怎么突然成业务员了",得答得上来。
 */
@Entity
@Table(name = "broker_origin", schema = "broker")
public class BrokerOrigin {

    public enum Origin {
        /** 自助注册(改动前唯一的路径)。 */
        SELF,
        /** 分享带来成交后自动升级。 */
        AUTO_UPGRADE,
        /** 站长授权。 */
        STATION_GRANT
    }

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Origin origin;

    @Column(name = "source_ref")
    private Long sourceRef;

    @Column(name = "granted_by")
    private Long grantedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected BrokerOrigin() { }

    public BrokerOrigin(long userId, Origin origin, Long sourceRef, Long grantedBy) {
        this.userId = userId;
        this.origin = origin;
        this.sourceRef = sourceRef;
        this.grantedBy = grantedBy;
    }

    public Long getUserId() { return userId; }
    public Origin getOrigin() { return origin; }
    public Long getSourceRef() { return sourceRef; }
    public Long getGrantedBy() { return grantedBy; }
    public Instant getCreatedAt() { return createdAt; }
}
