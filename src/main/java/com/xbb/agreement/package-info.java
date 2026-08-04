@org.springframework.modulith.ApplicationModule(
        // 白名单:这里没列的域一旦被引用,ModularityTests 就失败。
        // 之前所有域都没声明它,那个测试因此对生产代码恒绿。
        // identity:查协议归属时要判平台运维角色(见铁律 5.1)
        allowedDependencies = {"identity :: api", "ops :: api", "security"},
        displayName = "协议域"
)
package com.xbb.agreement;
