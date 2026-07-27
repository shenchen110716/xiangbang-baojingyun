package com.xbb;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 健康检查端点必须真的可达、且真的包含 outbox 这一项。
 *
 * <p>加了依赖、写了 HealthIndicator、测试全绿——这三件事都不能证明端点真的暴露出来了。
 * 本轮反复栽在这个模式上(注解在、守卫在、编译过,而那条路径根本没执行),
 * 所以这里直接打端点。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class HealthEndpointSmokeTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired MockMvc mvc;

    @Test
    void 健康检查端点可达且包含outbox积压() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.outbox").exists())
                .andExpect(jsonPath("$.components.outbox.details.卡死事件数").exists());
    }
}
