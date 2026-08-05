package com.xbb.broker.internal;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 某个服务站在某个业务类目上的分成比例。
 *
 * <p>{@code stationOrgId} 为 null 表示**平台默认**。让默认值和站点覆盖用同一张表,
 * 是为了取数只有一条路径 —— 分成两处维护的话,迟早出现两边不一致,
 * 而那种不一致要等对账才看得出来。
 */
@Entity
@Table(name = "station_rate", schema = "broker")
public class StationRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "station_org_id")
    private Long stationOrgId;

    @Column(nullable = false, length = 32)
    private String category;

    @Column(nullable = false)
    private int percent;

    @Column(name = "updated_by", nullable = false)
    private long updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    private long version;

    protected StationRate() { }

    public StationRate(Long stationOrgId, String category, int percent, long updatedBy) {
        this.stationOrgId = stationOrgId;
        this.category = category;
        this.percent = percent;
        this.updatedBy = updatedBy;
    }

    public void change(int percent, long updatedBy) {
        this.percent = percent;
        this.updatedBy = updatedBy;
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getStationOrgId() { return stationOrgId; }
    public String getCategory() { return category; }
    public int getPercent() { return percent; }
    public long getUpdatedBy() { return updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
}
