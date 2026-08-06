package com.xbb.broker.internal;

import jakarta.persistence.*;
import java.time.Instant;

/** 佣金比例的变更留痕。这是在改钱怎么算,事后要查得到是谁改的、改前是什么。 */
@Entity
@Table(name = "commission_rate_change", schema = "broker")
class CommissionRateChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String category;

    @Column(name = "region_code", length = 6)
    private String regionCode;

    @Column(name = "old_value")
    private String oldValue;

    @Column(name = "new_value", nullable = false)
    private String newValue;

    @Column(name = "changed_by", nullable = false)
    private long changedBy;

    @Column(nullable = false, length = 200)
    private String reason;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt = Instant.now();

    protected CommissionRateChange() { }

    CommissionRateChange(String category, String regionCode, String oldValue,
                         String newValue, long changedBy, String reason) {
        this.category = category;
        this.regionCode = regionCode;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.changedBy = changedBy;
        this.reason = reason;
    }
}
