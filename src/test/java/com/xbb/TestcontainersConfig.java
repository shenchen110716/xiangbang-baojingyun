package com.xbb;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    public static final PostgreSQLContainer<?> PG =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withInitScript("db/test-init.sql");

    static {
        PG.start();
    }

    /**
     * 环境修正:Spring 只会发现直接声明在测试类(或其外层类)上的 {@code @DynamicPropertySource}
     * 方法,声明在 {@code @Import} 进来的 {@code @TestConfiguration} 类里会被静默忽略。
     * 因此这里只提供可复用的注册逻辑,每个测试类需自带一个 {@code @DynamicPropertySource}
     * 方法调用本方法(见 SchemaIsolationTests 等)。
     */
    public static void registerProperties(DynamicPropertyRegistry registry) {
        // 应用运行时:受限用户
        registry.add("spring.datasource.url", PG::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "identity_user");
        registry.add("spring.datasource.password", () -> "identity_pw");
        // Flyway:管理员(需要 DDL 与 GRANT 权限)
        registry.add("spring.flyway.url", PG::getJdbcUrl);
        registry.add("spring.flyway.user", PG::getUsername);
        registry.add("spring.flyway.password", PG::getPassword);
    }

    @Bean
    PostgreSQLContainer<?> postgresContainer() {
        return PG;
    }
}
