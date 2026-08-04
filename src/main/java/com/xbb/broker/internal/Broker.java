package com.xbb.broker.internal;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 业务员(经纪人)。以 {@code parentUserId} 构成层级树,{@code stationOrgId} 归集到服务站。
 *
 * <p>结构照搬老系统的 Broker(parBrokerId / stationId / lastActiveTime),两处不同:
 * <ul>
 *   <li><b>根业务员用 null 而不是 0。</b>老系统拿 {@code parBrokerId = 0} 当哨兵,
 *       而 0 是个合法的 userId,迟早撞上。</li>
 *   <li><b>降级不物理删除。</b>老系统对没有下级的业务员直接 delete;
 *       删掉之后他名下已产生的佣金归属就断了,而 M10 文档自己要求"全程留痕"。</li>
 * </ul>
 */
@Entity
@Table(name = "broker", schema = "broker")
public class Broker {

    public enum Status { ACTIVE, DEMOTED }

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt = Instant.now();

    /** 所属服务站。null = 尚未挂靠。 */
    @Column(name = "station_org_id")
    private Long stationOrgId;

    /** 上级业务员。**null = 根业务员,永不降级**。 */
    @Column(name = "parent_user_id")
    private Long parentUserId;

    @Column(name = "last_active_at", nullable = false)
    private Instant lastActiveAt = Instant.now();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.ACTIVE;

    @Version
    private long version;

    protected Broker() { }

    public Broker(long userId) {
        this.userId = userId;
    }

    public Long getUserId() { return userId; }
    public Instant getRegisteredAt() { return registeredAt; }
    public Long getStationOrgId() { return stationOrgId; }
    public Long getParentUserId() { return parentUserId; }
    public Instant getLastActiveAt() { return lastActiveAt; }
    public Status getStatus() { return status; }

    /** 是不是根业务员。根不参与降级 —— 老系统同样豁免。 */
    public boolean isRoot() { return parentUserId == null; }

    void assignStation(Long stationOrgId) { this.stationOrgId = stationOrgId; }

    void assignParent(Long parentUserId) {
        if (parentUserId != null && parentUserId.equals(this.userId)) {
            throw new IllegalArgumentException("业务员不能是自己的上级");
        }
        this.parentUserId = parentUserId;
    }

    void touch() { this.lastActiveAt = Instant.now(); }

    void demote() { this.status = Status.DEMOTED; }

    void reinstate() {
        this.status = Status.ACTIVE;
        this.lastActiveAt = Instant.now();
    }
}
