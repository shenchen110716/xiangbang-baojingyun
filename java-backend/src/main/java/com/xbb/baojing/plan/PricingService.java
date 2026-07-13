package com.xbb.baojing.plan;

import com.xbb.baojing.agent.AgentCommission;
import com.xbb.baojing.common.ApiException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Ports backend/services/pricing.py 1:1 — the core pricing engine. Every
 * money figure is rounded to 2dp with the same "amount()" semantics as the
 * Python `round(value, 2)` helper. */
@Service
public class PricingService {
    private final PlanTierMapper planTierMapper;

    public PricingService(PlanTierMapper planTierMapper) { this.planTierMapper = planTierMapper; }

    public static double amount(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    public double planPriceForClass(InsurancePlan plan, String occupationClass) {
        if (occupationClass != null && !occupationClass.isBlank()) {
            PlanTier tier = planTierMapper.findActiveTier(plan.getId(), occupationClass);
            if (tier != null) return tier.getPrice();
        }
        return plan.getPrice();
    }

    public PricingSnapshot snapshot(InsurancePlan plan) {
        return snapshot(plan, null, null);
    }

    public PricingSnapshot snapshot(InsurancePlan plan, AgentCommission relation) {
        return snapshot(plan, relation, null);
    }

    public PricingSnapshot snapshot(InsurancePlan plan, AgentCommission relation, Double basePrice) {
        double insuranceBase = basePrice != null ? basePrice : plan.getPrice();
        double totalRate = plan.getCommissionRate();
        double totalCommission = insuranceBase * totalRate;
        double floor = insuranceBase - totalCommission;
        double profit = plan.getProfitAmount();
        double minimum = floor + profit;
        boolean priceMode = relation != null && (relation.getMode().equals("price") || relation.getMode().equals("markup"));
        double ratio = relation != null ? relation.getRate() : 0;
        double sale;
        double agentCommission;
        if (priceMode) {
            double configured = relation.getSalePrice();
            if (configured <= 0) configured = minimum + relation.getMarkupAmount();
            sale = Math.max(minimum, configured > 0 ? configured : minimum);
            agentCommission = Math.max(0, sale - minimum);
        } else {
            sale = minimum;
            agentCommission = insuranceBase * ratio;
        }

        PricingSnapshot s = new PricingSnapshot();
        s.setInsuranceBasePrice(amount(insuranceBase));
        s.setTotalCommissionRate(Math.round(totalRate * 1_000_000) / 1_000_000.0);
        s.setTotalCommissionAmount(amount(totalCommission));
        s.setPolicyFloorPrice(amount(floor));
        s.setInsurerSettlementPrice(amount(floor));
        s.setProfitAmount(amount(profit));
        s.setMinimumSalePrice(amount(minimum));
        s.setCommissionMode(priceMode ? "price" : "rebate");
        s.setAgentCommissionRate(priceMode ? 0 : Math.round(ratio * 1_000_000) / 1_000_000.0);
        s.setAgentCommissionAmount(amount(agentCommission));
        s.setSalePrice(amount(sale));
        s.setPlatformMarginAmount(priceMode ? amount(profit) : amount(Math.max(0, profit - agentCommission)));
        return s;
    }

    /** Ports validate_commission_price(): returns the resolved [mode, salePrice]. */
    public record ModeAndSale(String mode, double salePrice) {}

    public ModeAndSale validateCommissionPrice(String requestedMode, double rate, double requestedSalePrice, double markupAmount, InsurancePlan plan) {
        boolean priceMode = "price".equals(requestedMode) || "markup".equals(requestedMode);
        String mode = priceMode ? "price" : "rebate";
        double minimum = snapshot(plan).getMinimumSalePrice();
        if (!priceMode && rate > plan.getCommissionRate()) {
            throw ApiException.badRequest("业务员返佣比例不能超过产品总返佣比例");
        }
        double sale = requestedSalePrice;
        if (priceMode && sale <= 0) sale = minimum + markupAmount;
        if (priceMode && sale < minimum) {
            throw ApiException.badRequest(String.format("销售价格不能低于销售最低价 ¥%.2f", minimum));
        }
        return new ModeAndSale(mode, sale);
    }
}
