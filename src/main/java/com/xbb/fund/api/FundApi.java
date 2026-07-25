package com.xbb.fund.api;

import com.xbb.fund.internal.Payout;
import java.util.Optional;

public interface FundApi {

    record PayoutView(long id, long settlementId, long payeeUserId, long amountCents,
                       Payout.Status status) { }

    void disburse(long payoutId);

    Optional<PayoutView> findById(long payoutId);

    Optional<PayoutView> findBySettlementId(long settlementId);
}
