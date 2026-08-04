package com.xbb.attendance.internal;

import com.xbb.org.api.OrganizationApproved;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 显式命名:其它域也有同名类,默认 bean 名会撞车。 */
@Component("attendanceOrgEventListener")
class OrgEventListener {

    private final AttendanceApprovedOrgRepository orgs;

    OrgEventListener(AttendanceApprovedOrgRepository orgs) {
        this.orgs = orgs;
    }

    @EventListener
    @Transactional(transactionManager = "attendanceTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(OrganizationApproved event) {
        orgs.save(new ApprovedOrg(event.orgId(), event.legalRepUserId(), event.occurredAt()));
    }
}
