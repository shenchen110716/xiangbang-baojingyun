package com.xbb.broker.internal;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 某笔结算的佣金基数(只读副本)。
 *
 * <p>老系统拿浮动工资算佣金,不是应发总额。基数由结算域算出、随
 * {@code SettlementCalculated} 流过来,在这里存下 —— **不能指望"发放时结算事件已经处理过"**,
 * 两个事件的到达顺序不保证。
 */
@Entity
@Table(name = "commission_base", schema = "broker")
public class CommissionBase {

    @Id
    @Column(name = "settlement_id")
    private Long settlementId;

    @Column(name = "base_cents", nullable = false)
    private long baseCents;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt = Instant.now();

    protected CommissionBase() { }

    public CommissionBase(long settlementId, long baseCents) {
        this.settlementId = settlementId;
        this.baseCents = baseCents;
    }

    public Long getSettlementId() { return settlementId; }
    public long getBaseCents() { return baseCents; }
}
