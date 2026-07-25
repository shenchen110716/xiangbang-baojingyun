package com.xbb.fund.internal;

import com.xbb.fund.api.FundApi;
import com.xbb.fund.api.FundsDisbursed;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
class FundService implements FundApi {

    private final PayoutRepository payouts;
    private final ApplicationEventPublisher events;

    FundService(PayoutRepository payouts, ApplicationEventPublisher events) {
        this.payouts = payouts;
        this.events = events;
    }

    @Override
    @Transactional("fundTransactionManager")
    public void disburse(long payoutId) {
        Payout payout = payouts.findById(payoutId)
                .orElseThrow(() -> new IllegalArgumentException("发放记录不存在"));
        payout.disburse();
        payouts.save(payout);
        events.publishEvent(new FundsDisbursed(
                payoutId, payout.getSettlementId(), payout.getPayeeUserId(), payout.getAmountCents(), Instant.now()));
    }

    @Override
    @Transactional(transactionManager = "fundTransactionManager", readOnly = true)
    public Optional<PayoutView> findById(long payoutId) {
        return payouts.findById(payoutId).map(this::toView);
    }

    @Override
    @Transactional(transactionManager = "fundTransactionManager", readOnly = true)
    public Optional<PayoutView> findBySettlementId(long settlementId) {
        return payouts.findBySettlementId(settlementId).map(this::toView);
    }

    private PayoutView toView(Payout p) {
        return new PayoutView(p.getId(), p.getSettlementId(), p.getPayeeUserId(), p.getAmountCents(), p.getStatus());
    }
}
