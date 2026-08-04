package com.xbb.broker.internal;

import java.util.ArrayList;
import java.util.List;

/**
 * 六档佣金分账。**纯函数,不碰数据库** —— 分钱的算术要能单独验,
 * 混进仓储调用之后就只能靠搭一整套数据才测得动。
 *
 * <p>口径照搬老系统 JobComputerService:
 * <pre>
 *   主动   = 基数 × 主动比例                  → 直接经纪人
 *   剩余   = 基数 − 主动
 *     平台   = 剩余 × 平台比例
 *     被动池 = 剩余 × 被动比例  → 沿经纪人树向上逐级分,每级拿走当前剩余 × 逐级比例
 *     服务站 = 剩余 × 服务站比例
 * </pre>
 *
 * <p><b>全程整数(分)运算。</b>金额一旦碰浮点就会出现 0.999999 这类值,
 * 而分账是要写进账本的。每一档都向下取整,**取整余下的分不分给任何人** ——
 * 宁可少分几分钱,也不要因为进位让总额超过基数。
 */
final class CommissionSplitter {

    private CommissionSplitter() { }

    enum Tier { ACTIVE, PASSIVE, STATION }

    /** 一份分账。收款方是人(brokerUserId)或站(stationOrgId),恰好其一。 */
    record Share(Tier tier, Long brokerUserId, Long stationOrgId, int chainDepth, long amountCents) { }

    /** 平台那一份不进佣金表,单独返回——它记入资金域的平台收入账户。 */
    record Split(long platformCents, List<Share> shares) {

        long totalCents() {
            return platformCents + shares.stream().mapToLong(Share::amountCents).sum();
        }
    }

    record Rates(int activePct, int platformPct, int passivePct,
                 int passiveStepPct, int stationPct, long minPayoutCents) {

        /**
         * 平台 + 被动 + 服务站是**在同一块"剩余"里分**的,加起来超过 100 就等于凭空多分钱。
         * 这个校验放在配置入口(改参数、改服务站比例)执行,不是等到分钱的时候 ——
         * 分钱时才发现已经晚了:要么少给某一方,要么账不平。
         */
        void requireSane() {
            if (activePct < 0 || activePct > 100) {
                throw new IllegalArgumentException("主动佣金比例必须在 0 到 100 之间");
            }
            int remainderSum = platformPct + passivePct + stationPct;
            if (remainderSum > 100) {
                throw new IllegalArgumentException(
                        "平台(" + platformPct + "%)+ 被动(" + passivePct + "%)+ 服务站(" + stationPct
                        + "%)= " + remainderSum + "%,超过了可分配的 100%");
            }
            if (passiveStepPct <= 0 || passiveStepPct > 100) {
                throw new IllegalArgumentException("逐级被动分配比例必须在 1 到 100 之间");
            }
        }
    }

    /**
     * @param baseCents      基数(本次发放金额)
     * @param directBrokerId 直接经纪人
     * @param stationOrgId   直接经纪人所属服务站;为 null 时这一档不分
     * @param ancestors      直接经纪人往上的祖先链,由近及远。空表示他是根
     */
    static Split split(long baseCents, long directBrokerId, Long stationOrgId,
                       List<Long> ancestors, Rates rates) {
        rates.requireSane();
        if (baseCents <= 0) {
            return new Split(0, List.of());
        }
        List<Share> shares = new ArrayList<>();

        long active = baseCents * rates.activePct() / 100;
        if (active > 0) {
            shares.add(new Share(Tier.ACTIVE, directBrokerId, null, 0, active));
        }

        long remainder = baseCents - active;
        long platform = remainder * rates.platformPct() / 100;
        long station  = remainder * rates.stationPct()  / 100;
        long pool     = remainder * rates.passivePct()  / 100;

        if (stationOrgId != null && station > 0) {
            shares.add(new Share(Tier.STATION, null, stationOrgId, 0, station));
        }

        // 被动逐级:每一级拿走**当前剩余**的固定比例,所以越往上越少。
        // 低于下限就停 —— 不停的话会产生一串一分钱的流水,对账时全是噪音。
        int depth = 1;
        for (Long ancestor : ancestors) {
            if (pool < rates.minPayoutCents()) {
                break;
            }
            long share = pool * rates.passiveStepPct() / 100;
            if (share <= 0) {
                break;
            }
            shares.add(new Share(Tier.PASSIVE, ancestor, null, depth++, share));
            pool -= share;
        }

        // 取整余下的分留在池子里不分配。断言总额不超过基数——
        // 这是分钱代码,宁可在这里炸掉也不要让账面多出钱来。
        Split result = new Split(platform, shares);
        if (result.totalCents() > baseCents) {
            throw new IllegalStateException(
                    "分账总额 " + result.totalCents() + " 超过基数 " + baseCents + ",比例配置有误");
        }
        return result;
    }
}
