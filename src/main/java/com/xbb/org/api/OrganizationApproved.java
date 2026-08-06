package com.xbb.org.api;

import com.xbb.org.internal.Organization;

import java.time.Instant;

/**
 * 组织通过审核。
 *
 * <p>带上 {@code type} 是因为消费方需要区分主体类型 —— 尤其经纪人域:
 * 服务站是佣金结算单位,企业和工厂不是,拿不到类型就没法只replicate服务站。
 *
 * <p>{@code name} 与 {@code address} 也是后加的(2026-08-06):求职端岗位卡片要显示
 * "哪家单位、在哪上班",而岗位域不能直接读组织域(铁律 3),只能靠副本。
 * **和 type 一样,旧载荷里没有它们,重放时是 null,消费方必须容忍。**
 *
 * <p>兼容性:这个字段是后加的。**加之前已经落库的 outbox 载荷里没有它**,
 * 重放那些旧事件时 Jackson 会给 null。消费方必须容忍 null(当作"类型未知"),
 * 不能拿它直接做 switch。
 */
public record OrganizationApproved(long orgId, long legalRepUserId,
                                    com.xbb.org.api.OrgType type,
                                    String name, String address, Instant occurredAt) {

    /** 老的四参构造:重放历史载荷、以及不关心名称地址的调用方用。 */
    public OrganizationApproved(long orgId, long legalRepUserId,
                                com.xbb.org.api.OrgType type, Instant occurredAt) {
        this(orgId, legalRepUserId, type, null, null, occurredAt);
    }
}
