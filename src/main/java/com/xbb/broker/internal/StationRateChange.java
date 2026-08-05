package com.xbb.broker.internal;

import jakarta.persistence.*;

import java.time.Instant;

/** 分成比例变更留痕。这是在改钱怎么分,事后要查得到是谁改的。 */
@Entity
@Table(name = "station_rate_change", schema = "broker")
public class StationRateChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "station_org_id")
    private Long stationOrgId;

    @Column(nullable = false, length = 32)
    private String category;

    @Column(name = "old_percent")
    private Integer oldPercent;

    @Column(name = "new_percent", nullable = false)
    private int newPercent;

    @Column(name = "changed_by", nullable = false)
    private long changedBy;

    @Column(nullable = false, length = 200)
    private String reason;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt = Instant.now();

    protected StationRateChange() { }

    public StationRateChange(Long stationOrgId, String category, Integer oldPercent,
                             int newPercent, long changedBy, String reason) {
        this.stationOrgId = stationOrgId;
        this.category = category;
        this.oldPercent = oldPercent;
        this.newPercent = newPercent;
        this.changedBy = changedBy;
        this.reason = reason;
    }

    public Long getId() { return id; }
    public Long getStationOrgId() { return stationOrgId; }
    public String getCategory() { return category; }
    public Integer getOldPercent() { return oldPercent; }
    public int getNewPercent() { return newPercent; }
    public long getChangedBy() { return changedBy; }
    public String getReason() { return reason; }
    public Instant getChangedAt() { return changedAt; }
}
