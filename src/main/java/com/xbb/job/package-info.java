@org.springframework.modulith.ApplicationModule(
        // 白名单:这里没列的域一旦被引用,ModularityTests 就失败。
        // 之前所有域都没声明它,那个测试因此对生产代码恒绿。
        // identity(2026-08-06 加):个人发单要判断发单人有没有实名。
        // 岗位域没有已实名用户的副本(job.verified_user 在 V3 就删了),
        // 所以现查身份域 —— 实名是授权依据,副本晚到一秒就有人能钻空子
        // 分账**不在这里算**:比例配在经纪人域,而 job → broker 会闭合一个环
        // (attendance → engagement → job → broker → fund → settlement → attendance)。
        // 组装放在控制器那一层 —— 它本来就依赖所有域。
        // **这个环是 ModularityTests 抓出来的**:我先只看了 broker 的直接依赖里
        // 没有 job 就断定不成环,漏了传递路径
        allowedDependencies = {"identity :: api", "org :: api", "security"},
        displayName = "工作域"
)
package com.xbb.job;
