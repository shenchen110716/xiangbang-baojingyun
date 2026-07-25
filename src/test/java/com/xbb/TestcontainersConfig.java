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
                    .withInitScript("db/test-init.sql")
                    // 每加一个域,每个缓存的 Spring 测试上下文就多一个连接池;
                    // 默认 max_connections=100 在 4 个域之后已经不够用(实测触发过),
                    // 300 在加到 6 个域(引入 fund 域)之后同样不够用(实测触发过:
                    // "Unable to determine Dialect without JDBC metadata"——表面报错像配置错误,
                    // 实际根因是拿不到连接)。
                    .withCommand("postgres", "-c", "max_connections=600");

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
        // identity 域:应用运行时用受限用户,Flyway 用管理员
        registry.add("xbb.domains.identity.datasource.url", PG::getJdbcUrl);
        registry.add("xbb.domains.identity.datasource.username", () -> "identity_user");
        registry.add("xbb.domains.identity.datasource.password", () -> "identity_pw");
        registry.add("xbb.domains.identity.flyway.url", PG::getJdbcUrl);
        registry.add("xbb.domains.identity.flyway.user", PG::getUsername);
        registry.add("xbb.domains.identity.flyway.password", PG::getPassword);
        // org 域:同一个容器,不同 schema/用户
        registry.add("xbb.domains.org.datasource.url", PG::getJdbcUrl);
        registry.add("xbb.domains.org.datasource.username", () -> "org_user");
        registry.add("xbb.domains.org.datasource.password", () -> "org_pw");
        registry.add("xbb.domains.org.flyway.url", PG::getJdbcUrl);
        registry.add("xbb.domains.org.flyway.user", PG::getUsername);
        registry.add("xbb.domains.org.flyway.password", PG::getPassword);
        // job 域:同一个容器,不同 schema/用户
        registry.add("xbb.domains.job.datasource.url", PG::getJdbcUrl);
        registry.add("xbb.domains.job.datasource.username", () -> "job_user");
        registry.add("xbb.domains.job.datasource.password", () -> "job_pw");
        registry.add("xbb.domains.job.flyway.url", PG::getJdbcUrl);
        registry.add("xbb.domains.job.flyway.user", PG::getUsername);
        registry.add("xbb.domains.job.flyway.password", PG::getPassword);
        // settlement 域:同一个容器,不同 schema/用户
        registry.add("xbb.domains.settlement.datasource.url", PG::getJdbcUrl);
        registry.add("xbb.domains.settlement.datasource.username", () -> "settlement_user");
        registry.add("xbb.domains.settlement.datasource.password", () -> "settlement_pw");
        registry.add("xbb.domains.settlement.flyway.url", PG::getJdbcUrl);
        registry.add("xbb.domains.settlement.flyway.user", PG::getUsername);
        registry.add("xbb.domains.settlement.flyway.password", PG::getPassword);
        // fund 域:同一个容器,不同 schema/用户
        registry.add("xbb.domains.fund.datasource.url", PG::getJdbcUrl);
        registry.add("xbb.domains.fund.datasource.username", () -> "fund_user");
        registry.add("xbb.domains.fund.datasource.password", () -> "fund_pw");
        registry.add("xbb.domains.fund.flyway.url", PG::getJdbcUrl);
        registry.add("xbb.domains.fund.flyway.user", PG::getUsername);
        registry.add("xbb.domains.fund.flyway.password", PG::getPassword);
        // broker 域:同一个容器,不同 schema/用户
        registry.add("xbb.domains.broker.datasource.url", PG::getJdbcUrl);
        registry.add("xbb.domains.broker.datasource.username", () -> "broker_user");
        registry.add("xbb.domains.broker.datasource.password", () -> "broker_pw");
        registry.add("xbb.domains.broker.flyway.url", PG::getJdbcUrl);
        registry.add("xbb.domains.broker.flyway.user", PG::getUsername);
        registry.add("xbb.domains.broker.flyway.password", PG::getPassword);
        // profile 域:同一个容器,不同 schema/用户
        registry.add("xbb.domains.profile.datasource.url", PG::getJdbcUrl);
        registry.add("xbb.domains.profile.datasource.username", () -> "profile_user");
        registry.add("xbb.domains.profile.datasource.password", () -> "profile_pw");
        registry.add("xbb.domains.profile.flyway.url", PG::getJdbcUrl);
        registry.add("xbb.domains.profile.flyway.user", PG::getUsername);
        registry.add("xbb.domains.profile.flyway.password", PG::getPassword);
    }

    @Bean
    PostgreSQLContainer<?> postgresContainer() {
        return PG;
    }
}
