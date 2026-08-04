@org.springframework.modulith.ApplicationModule(
        // 白名单:这里没列的域一旦被引用,ModularityTests 就失败。
        allowedDependencies = {"engagement :: api", "org :: api", "identity :: api", "security"},
        displayName = "考勤域"
)
package com.xbb.attendance;
