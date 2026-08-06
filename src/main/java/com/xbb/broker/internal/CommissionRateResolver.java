package com.xbb.broker.internal;

import org.springframework.stereotype.Component;

/**
 * 按类目 + 地区取佣金比例,**从细到粗,先配到的赢**。
 *
 * <p>取不到时**不给默认值,直接报错**。给一个"就按 10% 吧"的兜底,
 * 总价岗位一上线就会按那个我编出来的数字扣钱,而没有任何人知道 ——
 * 这和 commission_scheme 那次不同:那次是把已有行为原样搬过来,
 * 这里是全新的口径,没有"原样"可搬。
 */
@Component
class CommissionRateResolver {

    private final CommissionRateRepository rates;

    CommissionRateResolver(CommissionRateRepository rates) {
        this.rates = rates;
    }

    CommissionRate resolve(String category, String regionCode) {
        for (String candidate : RegionScope.candidates(regionCode)) {
            var hit = candidate == null
                    ? rates.findByCategoryAndRegionCodeIsNull(category)
                    : rates.findByCategoryAndRegionCode(category, candidate);
            if (hit.isPresent()) {
                return hit.get();
            }
        }
        throw new IllegalStateException(
                "类目 " + category + "、地区 " + (regionCode == null ? "未填" : regionCode)
                + " 没有配佣金比例,连全国兜底都没有。请先到后台配置 —— "
                + "这里不给默认值,编一个数字出来就是在拿别人的钱冒险");
    }
}
