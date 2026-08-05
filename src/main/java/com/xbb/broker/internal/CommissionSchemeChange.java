package com.xbb.broker.internal;

import jakarta.persistence.*;

import java.time.Instant;

/** 分配方案变更留痕。这是在改钱怎么分,事后要查得到是谁改的、改前是什么。 */
@Entity
@Table(name = "commission_scheme_change", schema = "broker")
public class CommissionSchemeChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "station_org_id")
    private Long stationOrgId;

    @Column(nullable = false, length = 32)
    private String category;

    @Column(name = "old_value", columnDefinition = "text")
    private String oldValue;

    @Column(name = "new_value", nullable = false, columnDefinition = "text")
    private String newValue;

    @Column(name = "changed_by", nullable = false)
    private long changedBy;

    @Column(nullable = false, length = 200)
    private String reason;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt = Instant.now();

    protected CommissionSchemeChange() { }

    public CommissionSchemeChange(Long stationOrgId, String category, String oldValue,
                                  String newValue, long changedBy, String reason) {
        this.stationOrgId = stationOrgId;
        this.category = category;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.changedBy = changedBy;
        this.reason = reason;
    }

    public Long getId() { return id; }
    public Long getStationOrgId() { return stationOrgId; }
    public String getCategory() { return category; }
    public String getOldValue() { return oldValue; }
    public String getNewValue() { return newValue; }
    public long getChangedBy() { return changedBy; }
    public String getReason() { return reason; }
    public Instant getChangedAt() { return changedAt; }
}
