package com.xbb.broker.internal;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 总价模式下的佣金比例,按类目 + 地区配。
 *
 * <p>这是**上面那一层**:决定总价里有多少进佣金池、派遣公司先拿走多少。
 * 池子往下怎么分是 {@link CommissionScheme} 的事。
 */
@Entity
@Table(name = "commission_rate", schema = "broker")
class CommissionRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String category;

    /** NULL = 全国兜底。 */
    @Column(name = "region_code", length = 6)
    private String regionCode;

    @Column(name = "commission_pct", nullable = false)
    private int commissionPct;

    @Column(name = "dispatch_retain_pct", nullable = false)
    private int dispatchRetainPct;

    /** 收留存的派遣公司。留存为 0 时可以为 null,由数据库 CHECK 保证一致。 */
    @Column(name = "dispatch_org_id")
    private Long dispatchOrgId;

    @Column(name = "updated_by", nullable = false)
    private long updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    private long version;

    protected CommissionRate() { }

    CommissionRate(String category, String regionCode, int commissionPct,
                   int dispatchRetainPct, Long dispatchOrgId, long updatedBy) {
        this.category = category;
        this.regionCode = regionCode;
        apply(commissionPct, dispatchRetainPct, dispatchOrgId, updatedBy);
    }

    /**
     * 应用层也校验一遍,和数据库 CHECK 说的是同一件事。
     * **不是重复** —— 到了数据库报的是约束名,运营看不懂该改什么。
     */
    void apply(int commissionPct, int dispatchRetainPct, Long dispatchOrgId, long updatedBy) {
        if (commissionPct < 0 || commissionPct > 100) {
            throw new IllegalArgumentException("佣金比例要在 0~100 之间,当前 " + commissionPct);
        }
        if (dispatchRetainPct < 0 || dispatchRetainPct > 100) {
            throw new IllegalArgumentException("派遣留存比例要在 0~100 之间,当前 " + dispatchRetainPct);
        }
        if (dispatchRetainPct > 0 && dispatchOrgId == null) {
            // 留了钱却没人收:那笔钱从佣金池里扣掉、挂不到任何收款方,
            // 对账时是一个凭空消失的窟窿,而且要等月底才看得见
            throw new IllegalArgumentException("留了派遣比例就必须指定收款的派遣公司");
        }
        this.commissionPct = commissionPct;
        this.dispatchRetainPct = dispatchRetainPct;
        this.dispatchOrgId = dispatchOrgId;
        this.updatedBy = updatedBy;
        this.updatedAt = Instant.now();
    }

    /** 变更留痕用的可读快照。 */
    String summary() {
        return "佣金%d%% 派遣留存%d%% 派遣公司%s".formatted(
                commissionPct, dispatchRetainPct, dispatchOrgId == null ? "无" : "#" + dispatchOrgId);
    }

    Long getId() { return id; }
    String getCategory() { return category; }
    /** @return null 表示全国 */
    String getRegionCode() { return regionCode; }
    int getCommissionPct() { return commissionPct; }
    int getDispatchRetainPct() { return dispatchRetainPct; }
    Long getDispatchOrgId() { return dispatchOrgId; }
    Instant getUpdatedAt() { return updatedAt; }
}
