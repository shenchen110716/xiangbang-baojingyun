package com.xbb.fund.internal;

import com.xbb.fund.api.GuaranteeContext;
import com.xbb.fund.api.GuaranteeDecision;
import com.xbb.fund.api.GuaranteePolicy;
import org.springframework.stereotype.Component;

/**
 * v1.0 的担保策略实现(§8.3):按信用分段收押。
 *
 * <p>"注意:v1.0 的押金实现**已经是'按信用分段'**,比现有系统'人人必交'进了一步,
 * 又没违背'保留押金'的业务决定。**免押比例(信用 ≥80 的用户占比)就是押金退出坡道的
 * 观测指标**。"
 *
 * <p>分段照 §5.3.3:≥80 免押 / 60–79 半额 / 40–59 全额 / <40 全额且限制报名。
 * 纯函数,无副作用,不碰钱。
 */
@Component
class CreditBasedDepositPolicy implements GuaranteePolicy {

    /** 押金基准 = 岗位薪资的这个比例。 */
    static final double FULL_RATE = 0.5;

    @Override
    public GuaranteeDecision decide(GuaranteeContext ctx) {
        long full = Math.round(ctx.jobSalaryCents() * FULL_RATE);

        if (ctx.creditScore() >= 80) {
            return new GuaranteeDecision(false, 0,
                    "信用分 %d,免押金".formatted(ctx.creditScore()));
        }
        if (ctx.creditScore() >= 60) {
            return new GuaranteeDecision(true, full / 2,
                    "信用分 %d,按半额收取保证金".formatted(ctx.creditScore()));
        }
        if (ctx.creditScore() >= 40) {
            return new GuaranteeDecision(true, full,
                    "信用分 %d,按全额收取保证金".formatted(ctx.creditScore()));
        }
        return new GuaranteeDecision(true, full,
                "信用分 %d 偏低,需全额保证金,且报名可能受限,建议先申诉或完成几单积累记录"
                        .formatted(ctx.creditScore()));
    }
}
