package com.xbb.job.internal;

import com.xbb.org.api.OrganizationApproved;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;


@Component
class OrgEventListener {

    private final ApprovedOrgRepository approvedOrgs;

    OrgEventListener(ApprovedOrgRepository approvedOrgs) {
        this.approvedOrgs = approvedOrgs;
    }

    /**
     * `@EventListener` 而非 AFTER_COMMIT:该事件由发布方的 outbox 中继投递。
     * AFTER_COMMIT 的监听器要等中继事务提交后才跑,那时 outbox 行已是 PUBLISHED,
     * 这里再抛异常事件就永久丢了(理由详见 AbstractOutboxRelay)。
     */
    @EventListener
    @Transactional(transactionManager = "jobTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(OrganizationApproved event) {
        // 已有就刷新而不是整行替换。**整行替换的话,一条没带名称的事件
        // (比如只改站长的那条、或重放的旧载荷)会把单位名抹成空白** ——
        // 而求职端岗位卡片全靠它,抹掉了没有任何报错
        approvedOrgs.findById(event.orgId()).ifPresentOrElse(
                existing -> {
                    existing.refresh(event.legalRepUserId(), event.occurredAt(),
                            event.name(), event.address());
                    approvedOrgs.save(existing);
                },
                () -> approvedOrgs.save(new ApprovedOrg(event.orgId(), event.legalRepUserId(),
                        event.occurredAt(), event.name(), event.address())));
    }
}
