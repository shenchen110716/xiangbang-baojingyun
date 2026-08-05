@org.springframework.modulith.ApplicationModule(
        // 白名单:这里没列的域一旦被引用,ModularityTests 就失败。
        // 之前所有域都没声明它,那个测试因此对生产代码恒绿。
        // ops:借支额度上限来自参数中心,不写死(见 SettingKeys.ADVANCE_MAX_OUTSTANDING_CENTS)
        allowedDependencies = {"identity :: api", "ops :: api", "review :: api", "security", "settlement :: api"},
        displayName = "资金域"
)
package com.xbb.fund;
