package com.xbb.reimbursement.api;

import java.util.Optional;

/** §6.5.2 报销:本域只管**流程与审批**,打款一律走资金域。 */
public interface ReimbursementApi {

    enum Status { SUBMITTED, APPROVED, REJECTED, PAID }

    record ReimbursementView(long id, long applicantUserId, long amountCents, String reason,
                              String invoiceNo, Status status, String rejectReason) { }

    long submit(long applicantUserId, long amountCents, String reason, String invoiceNo);

    /** 审批通过 → 立即经资金域打款。**报销域自己不碰钱**。 */
    void approve(long reimbursementId, long approverUserId);

    void reject(long reimbursementId, long approverUserId, String rejectReason);

    /** 驳回后可以改金额/事由重新提交。 */
    void resubmit(long reimbursementId, long applicantUserId, long amountCents, String reason);

    Optional<ReimbursementView> find(long reimbursementId);
}
