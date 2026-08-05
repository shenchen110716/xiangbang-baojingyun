package com.xbb.broker.internal;

import com.xbb.org.api.OrganizationApproved;
import com.xbb.org.internal.Organization;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 显式命名:其它域也有同名类,默认 bean 名会撞车。 */
@Component("brokerOrgEventListener")
class OrgEventListener {

    private final StationRepository stations;

    OrgEventListener(StationRepository stations) {
        this.stations = stations;
    }

    /**
     * 只复制服务站。企业和工厂通过审核跟经纪人网络没关系,
     * 全部收进来会让"有多少个服务站"这种问题需要额外过滤才能答对。
     *
     * <p>type 可能为 null —— 它是后加的字段,更早落库的 outbox 载荷里没有。
     * 重放旧事件时当作"不是服务站"跳过:宁可漏建一个副本(平台能手工补),
     * 也不要把企业错当成服务站建进来。
     */
    @EventListener
    @Transactional(transactionManager = "brokerTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(OrganizationApproved event) {
        if (event.type() != com.xbb.org.api.OrgType.SERVICE_STATION) {
            return;
        }
        // 幂等:至少一次投递,同一个组织可能被通知多次。
        //
        // **站长要跟着更新,名称与比例不动。**这两者的来源不同:
        // 站长的权威值在组织域(平台可以换人),而名称与分成比例是平台在本域维护的,
        // 被重放覆盖就白设了。
        //
        // 早先这里是空实现(那时站长等于提交人、永不改变)。平台能换站长之后,
        // 不同步就成了洞:**换了人之后新站长签不了联合协议,老站长反而还能签**。
        stations.findById(event.orgId()).ifPresentOrElse(
                existing -> {
                    existing.changeLegalRep(event.legalRepUserId());
                    stations.save(existing);
                },
                () -> stations.save(new Station(event.orgId(), "服务站 #" + event.orgId(),
                        event.legalRepUserId(), event.occurredAt())));
    }
}
