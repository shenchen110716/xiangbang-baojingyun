@org.springframework.modulith.ApplicationModule(
        // 白名单:这里没列的域一旦被引用,ModularityTests 就失败。
        // 之前所有域都没声明它,那个测试因此对生产代码恒绿。
        // 本域不依赖任何其它域
        allowedDependencies = {},
        displayName = "商城域"
)
package com.xbb.mall;
