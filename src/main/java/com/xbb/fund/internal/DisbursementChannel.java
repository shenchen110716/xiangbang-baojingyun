package com.xbb.fund.internal;

/**
 * 代发通道防腐层(§6.4 微工卡)。
 *
 * <p>"经微工卡通道向工人代发**劳务报酬/经营所得**,平台**代征代缴**,出**完税凭证**。"
 * 主文档定性:"微工卡解决的核心是**税务合规**...这是灵活用工能否规模化的
 * **生死线,不是可选项**。"
 *
 * <p>真实对接微工卡时换实现,资金域的账务逻辑一行不动。
 */
public interface DisbursementChannel {

    /** 收款方式。§6.4.2:"工人默认收微信零钱,可绑银行卡"。 */
    enum PayeeAccount { WECHAT_BALANCE, BANK_CARD }

    record Receipt(String externalRef, String taxCertificateNo) { }

    /** 通道侧失败(余额不足、账户异常等),资金域据此记 FAILED 并允许重发。 */
    class ChannelException extends RuntimeException {
        public ChannelException(String message) { super(message); }
    }

    /**
     * @param idempotencyKey 唯一单号。通道侧也应按它去重——重发不能重复打钱
     */
    Receipt disburse(String idempotencyKey, long payeeUserId, long amountCents, PayeeAccount account);
}
