package com.xbb.broker.internal;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 某个类目下的**一整套分配方案**:主动、平台、被动、服务站、逐级、下限。
 *
 * <p>{@code stationOrgId} 为 null 表示平台默认。
 *
 * <p>此前只有服务站那一档能按类目设,其余五档全局共用 —— 而岗位、商品、培训的
 * 分账结构本来就不同(商品可能没有被动佣金,培训可能主动佣金极高),
 * 用同一套比例去分,任何一个类目都是错的。
 */
@Entity
@Table(name = "commission_scheme", schema = "broker")
public class CommissionScheme {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "station_org_id")
    private Long stationOrgId;

    @Column(nullable = false, length = 32)
    private String category;

    @Column(name = "active_pct", nullable = false)
    private int activePct;

    @Column(name = "platform_pct", nullable = false)
    private int platformPct;

    @Column(name = "passive_pct", nullable = false)
    private int passivePct;

    @Column(name = "station_pct", nullable = false)
    private int stationPct;

    @Column(name = "passive_step_pct", nullable = false)
    private int passiveStepPct;

    @Column(name = "min_payout_cents", nullable = false)
    private long minPayoutCents;

    @Column(name = "updated_by", nullable = false)
    private long updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    private long version;

    protected CommissionScheme() { }

    public CommissionScheme(Long stationOrgId, String category, int activePct, int platformPct,
                            int passivePct, int stationPct, int passiveStepPct,
                            long minPayoutCents, long updatedBy) {
        this.stationOrgId = stationOrgId;
        this.category = category;
        apply(activePct, platformPct, passivePct, stationPct, passiveStepPct, minPayoutCents, updatedBy);
    }

    /**
     * 改方案。校验放在这里,是为了让**每一条写入路径**都过同一道关 ——
     * 放在服务层的话,以后多一个入口就可能绕过去。
     */
    public final void apply(int activePct, int platformPct, int passivePct, int stationPct,
                            int passiveStepPct, long minPayoutCents, long updatedBy) {
        for (int p : new int[]{activePct, platformPct, passivePct, stationPct, passiveStepPct}) {
            if (p < 0 || p > 100) {
                throw new IllegalArgumentException("各档比例必须在 0 到 100 之间");
            }
        }
        if (platformPct + passivePct + stationPct > 100) {
            // 这三档在同一块"剩余"里分。超过 100 就是凭空多分钱,
            // 而分钱那一刻才发现已经晚了:要么少给某一方,要么账不平
            throw new IllegalArgumentException(String.format(
                    "平台 %d%% + 被动 %d%% + 服务站 %d%% 超过 100%% —— 它们在同一块剩余里分",
                    platformPct, passivePct, stationPct));
        }
        if (minPayoutCents < 0) {
            throw new IllegalArgumentException("分账下限不能为负");
        }
        this.activePct = activePct;
        this.platformPct = platformPct;
        this.passivePct = passivePct;
        this.stationPct = stationPct;
        this.passiveStepPct = passiveStepPct;
        this.minPayoutCents = minPayoutCents;
        this.updatedBy = updatedBy;
        this.updatedAt = Instant.now();
    }

    /** 便于留痕与比对的一行摘要。 */
    public String summary() {
        return "主动%d/平台%d/被动%d/服务站%d/逐级%d/下限%d分"
                .formatted(activePct, platformPct, passivePct, stationPct, passiveStepPct, minPayoutCents);
    }

    public Long getId() { return id; }
    public Long getStationOrgId() { return stationOrgId; }
    public String getCategory() { return category; }
    public int getActivePct() { return activePct; }
    public int getPlatformPct() { return platformPct; }
    public int getPassivePct() { return passivePct; }
    public int getStationPct() { return stationPct; }
    public int getPassiveStepPct() { return passiveStepPct; }
    public long getMinPayoutCents() { return minPayoutCents; }
    public long getUpdatedBy() { return updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
}
