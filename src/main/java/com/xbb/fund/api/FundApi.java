package com.xbb.fund.api;

import com.xbb.fund.internal.Payout;
import java.util.List;
import java.util.Optional;

public interface FundApi {

    record PayoutView(long id, long settlementId, long payeeUserId, long amountCents,
                       Payout.Status status) { }

    /*
     * 下面三个查询都带 callerUserId。**不是多余的参数** ——
     * 早先它们不带,于是任何登录用户把编号从 1 数上去,就能读到别人的收款金额、
     * 完税凭证号,以及平台监管账户里有多少钱。详见 ObjectAccessAuditTest。
     */

    /** 代发结果(§6.4.2):完税凭证号是税务合规的凭据,不是可选字段。 */
    record DisbursementView(long id, long payoutId, long payeeUserId, long amountCents,
                             DisbursementStatus status, String externalRef,
                             String taxCertificateNo, String failReason, int retryCount) { }

    /**
     * 通过微工卡通道代发。失败不抛异常而是落 FAILED 记录并原路退回预扣款,
     * 由调用方决定何时重发(§6.4.2"失败可重发")。
     */
    void disburse(long payoutId, long callerUserId);

    /** 重发失败的代发。用同一个幂等单号,通道侧去重,不会重复打钱。 */
    void retryDisbursement(long payoutId, long callerUserId);

    Optional<PayoutView> findById(long payoutId, long callerUserId);

    /** 我的发放记录列表。 */
    List<PayoutView> listMyPayouts(long payeeUserId);

    Optional<PayoutView> findBySettlementId(long settlementId);

    Optional<DisbursementView> findDisbursement(long payoutId, long callerUserId);

    /** 用工企业入金到监管账户。 */
    void topUp(AccountType accountType, long amountCents, String reason);

    /**
     * 带幂等键的入账,并要求 {@link com.xbb.identity.api.Role#PLATFORM_OPS}。
     * 开成 HTTP 端点的那一版必须走这个 —— 无键入账重试一次就多一笔钱。
     */
    void topUp(AccountType accountType, long amountCents, String reason,
               String idempotencyKey, long callerUserId);

    /** 监管账户余额。**要平台运维** —— 这是平台自己的钱,不是谁都能看的。 */
    long balanceOf(AccountType accountType, long callerUserId);

    /** 域内/跨域在进程内取余额,不经过 HTTP,因而不做角色校验。**不要把它接到控制器上。** */
    long balanceOf(AccountType accountType);

    /**
     * 从指定监管账户出账(报销打款等)。**资金域是唯一动钱者**——
     * 别的域只能调这个接口发起,不能自己记账扣余额(§4.1 决策#1)。
     */
    /**
     * @param idempotencyKey 业务方给的唯一键(如 "reimbursement-42")。**必填**——
     *                       调用方与资金域是两个事务,调用方那边失败回滚时这笔钱
     *                       已经出去了,重试就会重复扣款。有了它资金域能认出
     *                       "这笔我付过了",重复调用直接返回。
     */
    void spendFromAccount(AccountType accountType, long amountCents, String reason, String idempotencyKey);

    /**
     * 担保决策(§8)。**只产出决策不扣款**——§8.2"策略只做决策,不碰钱;
     * 资金域仍是唯一执行者"。信用分取自本域订阅评价域得到的只读副本;
     * 没有信用记录时按新人 60 分处理("0 分等于把新人判死刑")。
     */
    GuaranteeDecision decideGuarantee(long userId, long jobId, long jobSalaryCents);
}
