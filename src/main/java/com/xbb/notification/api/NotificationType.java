package com.xbb.notification.api;

/**
 * 通知类型。**刻意只列真正需要打扰用户的事**——有事件就发等于通知泛滥,
 * 泛滥等于没有通知。
 */
public enum NotificationType {
    /** 待签协议:不签不能到岗,是明确待办 */
    AGREEMENT_PENDING,
    /** 履约完成:可以评价了 */
    ENGAGEMENT_COMPLETED,
    /** 工资到账:最该通知的一条,带完税凭证号 */
    WAGE_DISBURSED,
    /** 佣金产生(通知经纪人) */
    COMMISSION_GENERATED,
    /** 信用分变更:会影响押金与派单,必须让人知道 */
    CREDIT_SCORE_CHANGED
}
