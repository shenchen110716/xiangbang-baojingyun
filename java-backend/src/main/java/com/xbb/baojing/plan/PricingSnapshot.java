package com.xbb.baojing.plan;

/** Ports backend/services/pricing.py's pricing_snapshot() dict shape 1:1 —
 * field names match web/src/api/types.ts's PricingSnapshot exactly (via the
 * global SNAKE_CASE Jackson naming strategy in application.yml). */
public class PricingSnapshot {
    private double insuranceBasePrice;
    private double totalCommissionRate;
    private double totalCommissionAmount;
    private double policyFloorPrice;
    private double insurerSettlementPrice;
    private double profitAmount;
    private double minimumSalePrice;
    private String commissionMode;
    private double agentCommissionRate;
    private double agentCommissionAmount;
    private double salePrice;
    private double platformMarginAmount;

    public double getInsuranceBasePrice() { return insuranceBasePrice; }
    public void setInsuranceBasePrice(double v) { this.insuranceBasePrice = v; }
    public double getTotalCommissionRate() { return totalCommissionRate; }
    public void setTotalCommissionRate(double v) { this.totalCommissionRate = v; }
    public double getTotalCommissionAmount() { return totalCommissionAmount; }
    public void setTotalCommissionAmount(double v) { this.totalCommissionAmount = v; }
    public double getPolicyFloorPrice() { return policyFloorPrice; }
    public void setPolicyFloorPrice(double v) { this.policyFloorPrice = v; }
    public double getInsurerSettlementPrice() { return insurerSettlementPrice; }
    public void setInsurerSettlementPrice(double v) { this.insurerSettlementPrice = v; }
    public double getProfitAmount() { return profitAmount; }
    public void setProfitAmount(double v) { this.profitAmount = v; }
    public double getMinimumSalePrice() { return minimumSalePrice; }
    public void setMinimumSalePrice(double v) { this.minimumSalePrice = v; }
    public String getCommissionMode() { return commissionMode; }
    public void setCommissionMode(String v) { this.commissionMode = v; }
    public double getAgentCommissionRate() { return agentCommissionRate; }
    public void setAgentCommissionRate(double v) { this.agentCommissionRate = v; }
    public double getAgentCommissionAmount() { return agentCommissionAmount; }
    public void setAgentCommissionAmount(double v) { this.agentCommissionAmount = v; }
    public double getSalePrice() { return salePrice; }
    public void setSalePrice(double v) { this.salePrice = v; }
    public double getPlatformMarginAmount() { return platformMarginAmount; }
    public void setPlatformMarginAmount(double v) { this.platformMarginAmount = v; }
}
