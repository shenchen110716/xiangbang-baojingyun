package com.xbb.voice.internal;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 受控意图注册表(主文档 §7.3)。
 *
 * <p>"**LLM 只能从注册表里选,不能自由生成意图**——与技能标签受控词表同一个道理。
 * 否则意图空间爆炸,路由不可控。"
 */
public final class IntentRegistry {

    private IntentRegistry() { }

    /** 确认分级(§7.1)。 */
    public enum RiskLevel {
        /** 低风险:一句话直接执行(查岗位、看工资、打卡) */
        L1,
        /** 中风险及以上:回读 + 显式确认词(发单、报名、发薪、提现) */
        L2
    }

    /** 角色。语音界面里用户"可以说出任何话",所以角色必须参与路由决策。 */
    public enum Role { WORKER, ORG_LEGAL_REP }

    public record Intent(String name, RiskLevel risk, Set<Role> allowedRoles, List<String> samples) { }

    private static final List<Intent> INTENTS = List.of(
            new Intent("job.publish", RiskLevel.L2, Set.of(Role.ORG_LEGAL_REP),
                    List.of("要20个普工", "招人", "发个岗位")),
            new Intent("job.recall", RiskLevel.L2, Set.of(Role.ORG_LEGAL_REP),
                    List.of("撤回刚才那单", "刚才那个不要了")),
            new Intent("job.query", RiskLevel.L1, Set.of(Role.WORKER, Role.ORG_LEGAL_REP),
                    List.of("附近有什么活", "看看岗位")),
            new Intent("salary.query", RiskLevel.L1, Set.of(Role.WORKER),
                    List.of("我这个月多少钱", "查工资")),
            // 发薪是组织侧的资金操作,工人说出这句话必须在路由层就被挡掉
            new Intent("salary.disburse", RiskLevel.L2, Set.of(Role.ORG_LEGAL_REP),
                    List.of("给他发薪", "把工资发了"))
    );

    public static Optional<Intent> find(String intentName) {
        return INTENTS.stream().filter(i -> i.name().equals(intentName)).findFirst();
    }

    public static List<Intent> all() {
        return INTENTS;
    }
}
