package com.xbb;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.awaitility.Awaitility;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

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

    /** 独占 outbox 表的测试用的库,见 {@link #registerIsolatedProperties}。 */
    private static final String ISOLATED_DB = "xbb_outbox_isolated";

    static {
        PG.start();
        createIsolatedDatabase();
        // 跨域事件改走 outbox 之后,投递是真异步的:"下游那行还没出现"是合法的中间态,
        // 不是失败。而 untilAsserted 默认只吞 AssertionError,orElseThrow 这类
        // NoSuchElementException 会直接炸穿等待循环。让它一并重试到超时为止。
        Awaitility.ignoreExceptionsByDefault();
    }

    private static void createIsolatedDatabase() {
        try (Connection connection = DriverManager.getConnection(
                     PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + ISOLATED_DB);
        } catch (SQLException e) {
            throw new IllegalStateException("建隔离库失败", e);
        }
    }

    /**
     * 环境修正:Spring 只会发现直接声明在测试类(或其外层类)上的 {@code @DynamicPropertySource}
     * 方法,声明在 {@code @Import} 进来的 {@code @TestConfiguration} 类里会被静默忽略。
     * 因此这里只提供可复用的注册逻辑,每个测试类需自带一个 {@code @DynamicPropertySource}
     * 方法调用本方法(见 SchemaIsolationTests 等)。
     */
    /** 二十个域全在同一个容器里,靠 schema + 独立受限用户隔离。 */
    private static final List<String> DOMAINS = List.of(
            "identity", "org", "job", "engagement", "settlement", "fund", "broker", "profile",
            "matching", "review", "agreement", "voice", "talent", "notification", "content",
            "collab", "reimbursement", "mall", "ops", "reporting");

    /**
     * 供测试类的 {@code @DynamicPropertySource} 调用。
     *
     * <p>注意:outbox 中继间隔**不在这里设**。{@code @DynamicPropertySource} 的优先级高于
     * {@code @SpringBootTest(properties=...)},放这里的话单个测试类想覆盖也覆盖不掉。
     * 默认值放在 src/test/resources/application.properties,那里优先级最低,可被覆盖。
     */
    public static void registerProperties(DynamicPropertyRegistry registry) {
        registerProperties(registry, PG.getJdbcUrl());
    }

    /**
     * 把二十个域指向**另一个数据库**,用于需要独占 outbox 表的测试。
     *
     * <p>为什么需要:测试里各个 Spring 上下文会被缓存复用,它们的定时中继线程在整个 JVM
     * 生命期里一直跑,而 outbox 表是共享的。于是"本类关掉中继、由测试自己驱动投递"
     * 根本不成立——别的上下文的中继会抢先投递(或者用 SKIP LOCKED 把行锁走,
     * 让本类的中继直接跳过)。要断言"没人投递之前下游拿不到",只能真正独占这些表。
     *
     * <p>用户是集群级的,所以新库只要建出来,各域的 Flyway(管理员身份)会自己建 schema 并授权。
     */
    public static void registerIsolatedProperties(DynamicPropertyRegistry registry) {
        registerProperties(registry, isolatedJdbcUrl());
    }

    private static void registerProperties(DynamicPropertyRegistry registry, String jdbcUrl) {
        for (String domain : DOMAINS) {
            // 应用运行时用受限用户,Flyway 用管理员
            registry.add("xbb.domains." + domain + ".datasource.url", () -> jdbcUrl);
            registry.add("xbb.domains." + domain + ".datasource.username", () -> domain + "_user");
            registry.add("xbb.domains." + domain + ".datasource.password", () -> domain + "_pw");
            registry.add("xbb.domains." + domain + ".flyway.url", () -> jdbcUrl);
            registry.add("xbb.domains." + domain + ".flyway.user", PG::getUsername);
            registry.add("xbb.domains." + domain + ".flyway.password", PG::getPassword);
        }
    }

    private static String isolatedJdbcUrl() {
        String url = PG.getJdbcUrl();
        int dbStart = url.lastIndexOf('/') + 1;
        int dbEnd = url.indexOf('?', dbStart);
        return url.substring(0, dbStart) + ISOLATED_DB + (dbEnd < 0 ? "" : url.substring(dbEnd));
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
