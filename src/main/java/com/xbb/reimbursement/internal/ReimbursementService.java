package com.xbb.reimbursement.internal;

import com.xbb.fund.api.AccountType;
import com.xbb.fund.api.FundApi;
import com.xbb.reimbursement.api.ReimbursementApi;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
class ReimbursementService implements ReimbursementApi {

    private final ReimbursementRepository reimbursements;
    private final FundApi fundApi;

    ReimbursementService(ReimbursementRepository reimbursements, FundApi fundApi) {
        this.reimbursements = reimbursements;
        this.fundApi = fundApi;
    }

    @Override
    @Transactional("reimbursementTransactionManager")
    public long submit(long applicantUserId, long amountCents, String reason, String invoiceNo) {
        return reimbursements.save(
                new Reimbursement(applicantUserId, amountCents, reason, invoiceNo)).getId();
    }

    @Override
    @Transactional("reimbursementTransactionManager")
    public void approve(long reimbursementId, long approverUserId) {
        Reimbursement r = load(reimbursementId);
        r.approve(approverUserId);

        // §6.5.2:打款走资金域(唯一动钱者)。本域只是发起,不自己记账、不自己扣余额。
        // 从平台收入账户出——报销是平台的内部成本,不该动用户在途资金。
        // 幂等键必须给:这一步和本域事务是两个事务,钱出去之后本域再失败回滚,
        // 单据会退回待审批状态、可以再审批一次——没有键就会重复扣款,
        // 而且流水里认不出哪笔是重复的。
        fundApi.spendFromAccount(AccountType.PLATFORM_REVENUE, r.getAmountCents(),
                "报销打款 reimbursement#" + r.getId(), "reimbursement-" + r.getId());

        r.markPaid();
        reimbursements.save(r);
    }

    @Override
    @Transactional("reimbursementTransactionManager")
    public void reject(long reimbursementId, long approverUserId, String rejectReason) {
        Reimbursement r = load(reimbursementId);
        r.reject(approverUserId, rejectReason);
        reimbursements.save(r);
    }

    @Override
    @Transactional("reimbursementTransactionManager")
    public void resubmit(long reimbursementId, long applicantUserId, long amountCents, String reason) {
        Reimbursement r = load(reimbursementId);
        if (r.getApplicantUserId() != applicantUserId) {
            throw new IllegalStateException("只能重新提交自己的报销");
        }
        r.resubmit(amountCents, reason);
        reimbursements.save(r);
    }

    @Override
    @Transactional(transactionManager = "reimbursementTransactionManager", readOnly = true)
    public Optional<ReimbursementView> find(long reimbursementId) {
        return reimbursements.findById(reimbursementId).map(r -> new ReimbursementView(
                r.getId(), r.getApplicantUserId(), r.getAmountCents(), r.getReason(),
                r.getInvoiceNo(), r.getStatus(), r.getRejectReason()));
    }

    private Reimbursement load(long id) {
        return reimbursements.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("报销单不存在"));
    }
}
