@org.springframework.modulith.ApplicationModule(
        // 白名单:这里没列的域一旦被引用,ModularityTests 就失败。
        // 之前所有域都没声明它,那个测试因此对生产代码恒绿。
        // ops:借支额度上限来自参数中心,不写死(见 SettingKeys.ADVANCE_MAX_OUTSTANDING_CENTS)
        //
        // org(2026-08-06 加):资金账户改为**按单位分账**之后,
        // 动账前要判断"这个人是不是这家单位的法人代表"。
        // 走 api 包、**现查不缓存**(铁律 5)—— 换了法人代表之后,
        // 旧的那个人不该还能从这家单位账上把钱发出去。
        //
        // 加这条依赖是有代价的:资金域从此和组织域绑在一起了。
        // 替代方案是在 fund 里再存一份归属副本,但归属是**授权依据**,
        // 副本晚到一秒就意味着有人在那一秒里能动不该动的钱。
        allowedDependencies = {"identity :: api", "ops :: api", "org :: api",
                               "review :: api", "security", "settlement :: api"},
        displayName = "资金域"
)
package com.xbb.fund;
