package com.xbb.reporting.internal;

import com.xbb.reporting.api.ReportingApi;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
class ReportingService implements ReportingApi {

    private final LedgerFactRepository facts;
    private final OverheadRepository overheads;

    ReportingService(LedgerFactRepository facts, OverheadRepository overheads) {
        this.facts = facts;
        this.overheads = overheads;
    }

    /** 幂等落宽表:事件重复投递不该让盈亏翻倍。 */
    @Transactional("reportingTransactionManager")
    void record(Dimension dimension, long dimensionId, LedgerFact.EntryType type,
                 long amountCents, String source, Long referenceId, Instant occurredAt) {
        if (facts.findByDimensionAndDimensionIdAndSourceAndReferenceId(
                dimension, dimensionId, source, referenceId).isPresent()) {
            return;
        }
        facts.save(new LedgerFact(dimension, dimensionId, type, amountCents, source, referenceId, occurredAt));
    }

    @Override
    @Transactional("reportingTransactionManager")
    public long recordOverhead(String label, long amountCents, AllocationBasis basis) {
        return overheads.save(new Overhead(label, amountCents, basis)).getId();
    }

    /**
     * §6.6.2:某维度盈亏 = 收入 − 直接成本 − 分摊的公共费用。
     *
     * <p>分摊必须**不多不少**:分摊出去的总额要等于公共费用总额,
     * 不能凭空多出或漏掉钱。余数补给权重最大的那一方,避免整数除法把钱丢掉。
     */
    @Override
    @Transactional(transactionManager = "reportingTransactionManager", readOnly = true)
    public List<ProfitLoss> profitAndLoss(Dimension dimension, Map<Long, Long> weights) {
        Map<Long, long[]> agg = new LinkedHashMap<>();   // [revenue, directCost]
        for (LedgerFact f : facts.findByDimension(dimension)) {
            long[] row = agg.computeIfAbsent(f.getDimensionId(), k -> new long[2]);
            if (f.getEntryType() == LedgerFact.EntryType.REVENUE) row[0] += f.getAmountCents();
            else row[1] += f.getAmountCents();
        }

        Map<Long, Long> allocated = allocateOverhead(agg.keySet().stream().toList(), weights);

        List<ProfitLoss> result = new ArrayList<>();
        for (Map.Entry<Long, long[]> e : agg.entrySet()) {
            long revenue = e.getValue()[0];
            long cost = e.getValue()[1];
            long overhead = allocated.getOrDefault(e.getKey(), 0L);
            result.add(new ProfitLoss(dimension, e.getKey(), revenue, cost, overhead,
                    revenue - cost - overhead));
        }
        return result;
    }

    private Map<Long, Long> allocateOverhead(List<Long> dimensionIds, Map<Long, Long> weights) {
        Map<Long, Long> allocated = new LinkedHashMap<>();
        if (weights == null || weights.isEmpty() || dimensionIds.isEmpty()) return allocated;

        long total = overheads.findAll().stream().mapToLong(Overhead::getAmountCents).sum();
        if (total == 0) return allocated;

        long weightSum = dimensionIds.stream().mapToLong(id -> weights.getOrDefault(id, 0L)).sum();
        if (weightSum == 0) return allocated;

        long distributed = 0;
        Long heaviest = null;
        long heaviestWeight = -1;
        for (Long id : dimensionIds) {
            long w = weights.getOrDefault(id, 0L);
            long share = total * w / weightSum;
            allocated.put(id, share);
            distributed += share;
            if (w > heaviestWeight) { heaviestWeight = w; heaviest = id; }
        }
        // 整数除法的余数补给权重最大的一方,保证分摊总额 == 公共费用总额
        long remainder = total - distributed;
        if (remainder != 0 && heaviest != null) {
            allocated.merge(heaviest, remainder, Long::sum);
        }
        return allocated;
    }
}
