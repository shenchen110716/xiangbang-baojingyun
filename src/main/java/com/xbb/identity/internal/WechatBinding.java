package com.xbb.identity.internal;

import jakarta.persistence.*;

import java.time.Instant;

/** 微信 openid 与账号的绑定。 */
@Entity
@Table(name = "wechat_binding", schema = "identity")
public class WechatBinding {

    @Id
    @Column(name = "open_id", length = 64)
    private String openId;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(name = "union_id", length = 64)
    private String unionId;

    @Column(length = 64)
    private String nickname;

    @Column(name = "bound_at", nullable = false)
    private Instant boundAt = Instant.now();

    protected WechatBinding() { }

    public WechatBinding(String openId, long userId, String unionId, String nickname) {
        this.openId = openId;
        this.userId = userId;
        this.unionId = unionId;
        this.nickname = nickname;
    }

    public String getOpenId() { return openId; }
    public long getUserId() { return userId; }
    public String getUnionId() { return unionId; }
    public String getNickname() { return nickname; }
    public Instant getBoundAt() { return boundAt; }
}
