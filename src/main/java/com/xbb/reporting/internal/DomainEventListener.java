package com.xbb.reporting.internal;

import com.xbb.broker.api.CommissionGenerated;
import com.xbb.fund.api.FundsDisbursed;
import com.xbb.mall.api.OrderSettlementTriggered;
import com.xbb.reporting.api.ReportingApi;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;


/**
 * §6.6.1:报表域**订阅各域事件**建本域宽表,**绝不跨域 join 生产表**。
 * "否则报表一慢就拖垮交易,又回到旧系统'一个大查询锁全库'的老路。"
 */
@Component("reportingDomainEventListener")
class DomainEventListener {

    private final ReportingService reporting;

    DomainEventListener(ReportingService reporting) {
        this.reporting = reporting;
    }

    /** 工资发放:对工人是收入,对平台是直接成本。 */
    /**
     * `@EventListener` 而非 AFTER_COMMIT:该事件由资金域的 outbox 中继投递,
     * 中继在自己的事务里 publish——用 AFTER_COMMIT 的话本方法要等中继事务提交后才跑,
     * 那时 outbox 行已是 PUBLISHED,这里再抛异常事件就永久丢了。
     */
    @EventListener
    @Transactional(transactionManager = "reportingTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(FundsDisbursed event) {
        reporting.record(ReportingApi.Dimension.WORKER, event.payeeUserId(),
                LedgerFact.EntryType.REVENUE, event.amountCents(),
                "FUNDS_DISBURSED", event.payoutId(), event.occurredAt());
    }

    /** 佣金:对经纪人是收入。 */
    /**
     * `@EventListener` 而非 AFTER_COMMIT:所有跨域事件都由发布方的 outbox 中继投递。
     * AFTER_COMMIT 的监听器要等中继事务提交后才跑,那时 outbox 行已是 PUBLISHED,
     * 这里再抛异常事件就永久丢了(理由详见 AbstractOutboxRelay)。
     */
    @EventListener
    @Transactional(transactionManager = "reportingTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(CommissionGenerated event) {
        reporting.record(ReportingApi.Dimension.BROKER, event.brokerUserId(),
                LedgerFact.EntryType.REVENUE, event.amountCents(),
                "COMMISSION", event.commissionId(), event.occurredAt());
    }

    /** 商城订单结算:对商户是收入。两种触发模式殊途同归,这里不区分来源(§6.3.7)。 */
    /**
     * `@EventListener` 而非 AFTER_COMMIT:所有跨域事件都由发布方的 outbox 中继投递。
     * AFTER_COMMIT 的监听器要等中继事务提交后才跑,那时 outbox 行已是 PUBLISHED,
     * 这里再抛异常事件就永久丢了(理由详见 AbstractOutboxRelay)。
     */
    @EventListener
    @Transactional(transactionManager = "reportingTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(OrderSettlementTriggered event) {
        reporting.record(ReportingApi.Dimension.ORG, event.merchantId(),
                LedgerFact.EntryType.REVENUE, event.amountCents(),
                "MALL_ORDER", event.orderId(), event.occurredAt());
    }
}
