package com.xbb.fund.api;

import com.xbb.fund.internal.Payout;
import java.util.Optional;

public interface FundApi {

    record PayoutView(long id, long settlementId, long payeeUserId, long amountCents,
                       Payout.Status status) { }

    /** 代发结果(§6.4.2):完税凭证号是税务合规的凭据,不是可选字段。 */
    record DisbursementView(long id, long payoutId, long payeeUserId, long amountCents,
                             DisbursementStatus status, String externalRef,
                             String taxCertificateNo, String failReason, int retryCount) { }

    /**
     * 通过微工卡通道代发。失败不抛异常而是落 FAILED 记录并原路退回预扣款,
     * 由调用方决定何时重发(§6.4.2"失败可重发")。
     */
    void disburse(long payoutId);

    /** 重发失败的代发。用同一个幂等单号,通道侧去重,不会重复打钱。 */
    void retryDisbursement(long payoutId);

    Optional<PayoutView> findById(long payoutId);

    Optional<PayoutView> findBySettlementId(long settlementId);

    Optional<DisbursementView> findDisbursement(long payoutId);

    /** 用工企业入金到监管账户。 */
    void topUp(AccountType accountType, long amountCents, String reason);

    long balanceOf(AccountType accountType);

    /**
     * 担保决策(§8)。**只产出决策不扣款**——§8.2"策略只做决策,不碰钱;
     * 资金域仍是唯一执行者"。信用分取自本域订阅评价域得到的只读副本;
     * 没有信用记录时按新人 60 分处理("0 分等于把新人判死刑")。
     */
    GuaranteeDecision decideGuarantee(long userId, long jobId, long jobSalaryCents);
}
