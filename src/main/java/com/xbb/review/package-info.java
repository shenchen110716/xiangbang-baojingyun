@org.springframework.modulith.ApplicationModule(
        // 白名单:这里没列的域一旦被引用,ModularityTests 就失败。
        // 之前所有域都没声明它,那个测试因此对生产代码恒绿。
        // identity:查评价归属时要判平台运维角色(见铁律 5.1)
        allowedDependencies = {"engagement :: api", "identity :: api", "ops :: api", "org :: api", "security"},
        displayName = "评价域"
)
package com.xbb.review;
