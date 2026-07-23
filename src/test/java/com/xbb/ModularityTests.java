package com.xbb;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

    static final ApplicationModules MODULES = ApplicationModules.of(XbbApplication.class);

    @Test
    void 模块边界必须合法() {
        MODULES.verify();
    }

    @Test
    void 打印模块结构() {
        MODULES.forEach(System.out::println);
    }
}
