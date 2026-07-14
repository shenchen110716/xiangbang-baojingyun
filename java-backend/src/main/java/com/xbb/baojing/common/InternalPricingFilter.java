package com.xbb.baojing.common;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Ports backend/services/pricing.py's strip_internal_pricing(). Enterprise-
 * role callers (miniprogram end users, company HR accounts) should only ever
 * see the actual premium they're charged, not the platform's cost basis —
 * mirrors the split already used in the Excel export (PolicyController's
 * enterprise-export branch). Converts the DTO through the app's globally
 * snake_case-configured ObjectMapper so the key set matches Python's dict
 * keys exactly, then drops the internal ones. */
public final class InternalPricingFilter {
    private InternalPricingFilter() {}

    public static final Set<String> INTERNAL_FIELDS = Set.of(
            "insurance_base_price", "total_commission_rate", "total_commission_amount",
            "policy_floor_price", "insurer_settlement_price", "profit_amount",
            "commission_mode", "agent_commission_rate", "agent_commission_amount",
            "platform_margin_amount", "insurance_base_total", "policy_floor_total",
            "total_commission_total", "agent_commission_total",
            "price", "commission_rate"
    );

    @SuppressWarnings("unchecked")
    public static Object strip(Object out, User user, ObjectMapper mapper) {
        if (!"enterprise".equals(user.getRole())) return out;
        Map<String, Object> map = new LinkedHashMap<>(mapper.convertValue(out, Map.class));
        INTERNAL_FIELDS.forEach(map::remove);
        return map;
    }
}
