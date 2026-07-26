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
                    // 每加一个域,每个缓存的 Spring 测试上下文就多一个连接池,
                    // 所以需求量是 O(域数 × 缓存的上下文数),加域时呈乘性增长。
                    // 实测踩过四次:100 撑不到 4 个域、300 撑不到 6 个、600 撑不到 8 个、
                    // 900 撑不到 10 个。表面报错是 Hibernate
                    // "Unable to determine Dialect without JDBC metadata"(像配置错误),
                    // 实际根因是拿不到连接。
                    //
                    // 注意 max_connections 不能无脑往上加:它要占共享内存,容器默认 shm 只有
                    // 64MB,设到 2000 会让 postmaster 直接起不来——报错变成
                    // "Connection refused / total=0",看着像网络问题,其实是数据库没起来。
                    // 所以这里同时把 shm 提到 1GB,再给 1200 的余量。
                    .withSharedMemorySize(1024L * 1024 * 1024)
                    .withCommand("postgres", "-c", "max_connections=1200");

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
        // engagement 域:同一个容器,不同 schema/用户
        registry.add("xbb.domains.engagement.datasource.url", PG::getJdbcUrl);
        registry.add("xbb.domains.engagement.datasource.username", () -> "engagement_user");
        registry.add("xbb.domains.engagement.datasource.password", () -> "engagement_pw");
        registry.add("xbb.domains.engagement.flyway.url", PG::getJdbcUrl);
        registry.add("xbb.domains.engagement.flyway.user", PG::getUsername);
        registry.add("xbb.domains.engagement.flyway.password", PG::getPassword);
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
        // matching 域:同一个容器,不同 schema/用户
        registry.add("xbb.domains.matching.datasource.url", PG::getJdbcUrl);
        registry.add("xbb.domains.matching.datasource.username", () -> "matching_user");
        registry.add("xbb.domains.matching.datasource.password", () -> "matching_pw");
        registry.add("xbb.domains.matching.flyway.url", PG::getJdbcUrl);
        registry.add("xbb.domains.matching.flyway.user", PG::getUsername);
        registry.add("xbb.domains.matching.flyway.password", PG::getPassword);
        // review 域:同一个容器,不同 schema/用户
        registry.add("xbb.domains.review.datasource.url", PG::getJdbcUrl);
        registry.add("xbb.domains.review.datasource.username", () -> "review_user");
        registry.add("xbb.domains.review.datasource.password", () -> "review_pw");
        registry.add("xbb.domains.review.flyway.url", PG::getJdbcUrl);
        registry.add("xbb.domains.review.flyway.user", PG::getUsername);
        registry.add("xbb.domains.review.flyway.password", PG::getPassword);
    }

    /**
     * {@code destroyMethod = ""} 不能省。
     *
     * <p>容器是整个测试套件共享的静态单例(上面的 static 块启动一次)。但把它暴露成
     * {@code @Bean} 之后,Spring 会按约定推断出销毁方法 {@code close()} 并接管它的生命周期。
     * Spring 测试上下文缓存**默认上限 32 个**,测试类超过 32 个就会 LRU 淘汰最旧的上下文,
     * 淘汰时调用 {@code close()} —— 把全局共享的数据库容器关掉,之后所有测试全崩,
     * 报错还是 "Failed to bind xbb.domains.*.flyway.url"(因为 getJdbcUrl() 返回 null),
     * 看着完全像配置问题,跟真正的原因隔了十万八千里。
     *
     * <p>实测:测试类涨到 44 个时必现,且**单独跑必过、全量跑必挂**。
     */
    @Bean(destroyMethod = "")
    PostgreSQLContainer<?> postgresContainer() {
        return PG;
    }
}
