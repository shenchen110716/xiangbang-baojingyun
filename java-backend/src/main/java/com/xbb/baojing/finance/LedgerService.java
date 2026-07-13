package com.xbb.baojing.finance;

import com.xbb.baojing.common.User;
import com.xbb.baojing.enterprise.Enterprise;
import com.xbb.baojing.plan.PricingService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Ports backend/services/ledger.py — the append-only single-account ledger
 * (SYSTEM-DESIGN-V4.md 7.5). Callers are responsible for updating
 * Enterprise.premiumBalance/usageBalance and persisting both in the same
 * request as postEntry(), same contract as the Python version. */
@Service
public class LedgerService {
    private final LedgerMapper ledgerMapper;

    public LedgerService(LedgerMapper ledgerMapper) { this.ledgerMapper = ledgerMapper; }

    public void postEntry(Enterprise enterprise, String account, String direction, double value, String businessType, String businessId, User user) {
        postEntry(enterprise, account, direction, value, businessType, businessId, user, "");
    }

    public void postEntry(Enterprise enterprise, String account, String direction, double value, String businessType, String businessId, User user, String idempotencyKey) {
        LedgerEntry entry = new LedgerEntry();
        entry.setEnterpriseId(enterprise.getId());
        entry.setAccount(account);
        entry.setDirection(direction);
        entry.setAmount(BigDecimal.valueOf(PricingService.amount(value)));
        entry.setBusinessType(businessType);
        entry.setBusinessId(businessId == null ? "" : businessId);
        entry.setIdempotencyKey(idempotencyKey == null ? "" : idempotencyKey);
        entry.setCreatedBy(user != null ? user.getId() : null);
        entry.setOccurredAt(LocalDateTime.now());
        ledgerMapper.insert(entry);
    }

    public record Mismatch(String account, double cachedBalance, double ledgerBalance, double diff) {}

    public List<Mismatch> reconcile(Enterprise enterprise) {
        List<Mismatch> mismatches = new ArrayList<>();
        checkAccount(mismatches, enterprise, "premium", enterprise.getPremiumBalance());
        checkAccount(mismatches, enterprise, "usage", enterprise.getUsageBalance());
        return mismatches;
    }

    private void checkAccount(List<Mismatch> mismatches, Enterprise enterprise, String account, double cached) {
        BigDecimal credit = ledgerMapper.sumCredit(enterprise.getId(), account);
        BigDecimal debit = ledgerMapper.sumDebit(enterprise.getId(), account);
        double ledgerBalance = PricingService.amount(credit.subtract(debit).doubleValue());
        if (ledgerBalance != PricingService.amount(cached)) {
            mismatches.add(new Mismatch(account, PricingService.amount(cached), ledgerBalance, PricingService.amount(cached - ledgerBalance)));
        }
    }
}
