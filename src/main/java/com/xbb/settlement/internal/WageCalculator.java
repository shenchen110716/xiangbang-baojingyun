package com.xbb.settlement.internal;

import java.util.ArrayList;
import java.util.List;

/**
 * 按计薪方案与工时算应发工资。**纯函数,不碰数据库。**
 *
 * <p>和佣金分账一样的理由:钱的算术要能单独验。混进仓储调用之后就只能靠搭一整套数据
 * 才测得动,而取整、零工时、按月不按工时这些边界恰恰需要跑很多组。
 *
 * <p><b>全程整数(分)运算。</b>金额一旦碰浮点就会出现 0.999999 这类值,而这是要写进账本的。
 * 按小时计薪时先乘后除(分钟 × 时薪 ÷ 60),不是先除后乘 —— 后者会把不满一小时的部分抹掉。
 */
final class WageCalculator {

    private WageCalculator() { }

    enum PayType {
        /** 按小时:基本与浮动都按实际工时折算 */
        HOURLY,
        /** 按天:按出勤天数折算(有工时的那天算一天,不看时长) */
        DAILY,
        /** 按月:整期一笔,与工时无关 */
        MONTHLY,
        /** 按件:件数由外部给出,本类暂不支持 */
        PIECE
    }

    enum FactorType { BONUS, DEDUCTION, PENALTY }

    /** 调整项。金额一律正数,方向由类型决定。 */
    record Factor(FactorType type, String name, long amountCents) {

        boolean isAddition() { return type == FactorType.BONUS; }
    }

    record Plan(PayType payType, long basicSalaryCents, long floatSalaryCents,
                long fixedSalaryCents, List<Factor> factors) { }

    /** 一行明细。存进工资单是为了事后能解释金额怎么来的。 */
    record Line(String name, long amountCents) { }

    record Result(long basicCents, long floatCents, long fixedCents,
                  long additionsCents, long deductionsCents, List<Line> lines) {

        /** 应发。**下限为 0** —— 扣款超过应得时不产生负工资,详见 compute 的注释。 */
        long grossCents() {
            long raw = basicCents + floatCents + fixedCents + additionsCents - deductionsCents;
            return Math.max(0, raw);
        }

        /** 佣金基数。老系统拿浮动工资算佣金,不是拿应发总额。 */
        long commissionBaseCents() { return floatCents; }
    }

    /**
     * @param minutes  已确认的总工时(分钟)。**只该传已确认的** —— 草稿态还可能被订正
     * @param workDays 有出勤的天数,按天计薪时用
     */
    static Result compute(Plan plan, int minutes, int workDays) {
        if (minutes < 0 || workDays < 0) {
            throw new IllegalArgumentException("工时与出勤天数不能为负");
        }
        long basic;
        long floating;
        switch (plan.payType()) {
            case HOURLY -> {
                // 先乘后除:先除会把不满一小时的部分抹掉,一个月下来能差出一天工资
                basic    = plan.basicSalaryCents() * minutes / 60;
                floating = plan.floatSalaryCents() * minutes / 60;
            }
            case DAILY -> {
                basic    = plan.basicSalaryCents() * workDays;
                floating = plan.floatSalaryCents() * workDays;
            }
            case MONTHLY -> {
                // 整期一笔,与工时无关。**但零出勤不发** ——
                // 否则一个整月没来的人照样拿满月工资。
                boolean attended = minutes > 0 || workDays > 0;
                basic    = attended ? plan.basicSalaryCents() : 0;
                floating = attended ? plan.floatSalaryCents() : 0;
            }
            case PIECE -> throw new IllegalArgumentException(
                    "按件计薪需要件数,当前版本不支持。请用按小时或按天");
            default -> throw new IllegalStateException("未知计薪方式: " + plan.payType());
        }

        long fixed = plan.fixedSalaryCents();
        List<Line> lines = new ArrayList<>();
        if (basic > 0)    lines.add(new Line("基本工资", basic));
        if (floating > 0) lines.add(new Line("浮动工资", floating));
        if (fixed > 0)    lines.add(new Line("固定工资", fixed));

        long additions = 0;
        long deductions = 0;
        for (Factor f : plan.factors() == null ? List.<Factor>of() : plan.factors()) {
            if (f.amountCents() < 0) {
                // 金额存正数、方向由类型决定,是为了不让"负数的扣款"和"正数的扣款"两种写法并存
                throw new IllegalArgumentException("调整项金额必须为正数,方向由类型决定: " + f.name());
            }
            if (f.isAddition()) {
                additions += f.amountCents();
                lines.add(new Line(f.name(), f.amountCents()));
            } else {
                deductions += f.amountCents();
                lines.add(new Line(f.name(), -f.amountCents()));
            }
        }

        return new Result(basic, floating, fixed, additions, deductions, lines);
    }
}
