@org.springframework.modulith.ApplicationModule(
        // 白名单:这里没列的域一旦被引用,ModularityTests 就失败。
        // 之前所有域都没声明它,那个测试因此对生产代码恒绿。
        // identity(2026-08-06 加):个人发单要判断发单人有没有实名。
        // job.verified_user 那张副本表**存在但没有任何代码在维护**,
        // 靠它判断等于不判断 —— 所以现查身份域
        allowedDependencies = {"identity :: api", "org :: api", "security"},
        displayName = "工作域"
)
package com.xbb.job;
