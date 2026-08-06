package com.xbb.broker.internal;

import java.util.ArrayList;
import java.util.List;

/**
 * 地区的层级回退。
 *
 * <p>后台按**类目 + 地区**配佣金比例,但不可能每个区县都配一遍。
 * 取数时从最细往上找,**先配到的赢**:
 * 区县(6 位) → 市(前 4 位 + 00) → 省(前 2 位 + 0000) → 全国(null)。
 *
 * <p>用国标行政区划代码(GB/T 2260),因为它的层级就编码在数字里 ——
 * 换成"华东区""江浙沪"这种自定义分组的话,每加一个地区都要维护一张归属表,
 * 而那张表一旦和实际行政区划对不上,钱就分错了。
 *
 * <p><b>地区必须是选出来的,不能从地址文本里猜。</b>
 * "苏州市吴中区…"解析错了不会报错,只会静默套上另一个地区的比例。
 */
final class RegionScope {

    private RegionScope() { }

    /**
     * 从最细到最粗的候选,末尾是 null(全国)。
     *
     * <p>传进来的码不是 6 位数字时,只回退到全国 —— 与其猜它想表达哪一级,
     * 不如让它落到全国那条,至少是明确的一条。
     */
    static List<String> candidates(String regionCode) {
        List<String> out = new ArrayList<>(4);
        if (regionCode != null && regionCode.length() == 6 && regionCode.chars().allMatch(Character::isDigit)) {
            String county = regionCode;
            String city = regionCode.substring(0, 4) + "00";
            String province = regionCode.substring(0, 2) + "0000";
            out.add(county);
            // 本身就是市级/省级时不要重复添加，否则同一个码会被查两遍
            if (!city.equals(county)) {
                out.add(city);
            }
            if (!province.equals(city) && !province.equals(county)) {
                out.add(province);
            }
        }
        out.add(null);   // 全国兜底
        return out;
    }
}
