@org.springframework.modulith.ApplicationModule(
        // 白名单:这里没列的域一旦被引用,ModularityTests 就失败。
        // 之前所有域都没声明它,那个测试因此对生产代码恒绿。
        // identity/org(2026-08-07 审计加):人才库要判断"翻的人是不是用工方"。
        // 此前谁都能翻 —— 任何注册用户按编号就能扒别人的期望薪资和履约记录。
        // 传递闭包验过不成环
        allowedDependencies = {"engagement :: api", "identity :: api",
                               "org :: api", "profile :: api", "security"},
        displayName = "人才库域"
)
package com.xbb.talent;
