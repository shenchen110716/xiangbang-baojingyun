package com.xbb.broker.internal;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 业务员归属/状态变更留痕。
 *
 * <p>M10 设计文档要求"归属发生变更时全程留痕,可追溯操作人与前后值"。
 * 老系统只有表结构描述,降级那条路径上一行都没写 —— 业务员被删掉之后,
 * 他名下已产生的佣金归属就断了,出纠纷只能靠人回忆。
 */
@Entity
@Table(name = "broker_change_log", schema = "broker")
public class BrokerChangeLog {

    public enum ChangeType { STATION, PARENT, STATUS }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "broker_user_id", nullable = false)
    private long brokerUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 24)
    private ChangeType changeType;

    @Column(name = "old_value", length = 64)
    private String oldValue;

    @Column(name = "new_value", length = 64)
    private String newValue;

    /** 系统自动降级时没有操作人,记 null 而不是编一个 0 —— 0 是个合法的 userId。 */
    @Column(name = "changed_by")
    private Long changedBy;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt = Instant.now();

    @Column(length = 200)
    private String reason;

    protected BrokerChangeLog() { }

    BrokerChangeLog(long brokerUserId, ChangeType type, String oldValue, String newValue,
                    Long changedBy, String reason) {
        this.brokerUserId = brokerUserId;
        this.changeType = type;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.changedBy = changedBy;
        this.reason = reason;
    }

    public Long getId() { return id; }
    public long getBrokerUserId() { return brokerUserId; }
    public ChangeType getChangeType() { return changeType; }
    public String getOldValue() { return oldValue; }
    public String getNewValue() { return newValue; }
    public Long getChangedBy() { return changedBy; }
    public Instant getChangedAt() { return changedAt; }
    public String getReason() { return reason; }
}
