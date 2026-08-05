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
        // 跨域事件改走 outbox 之后,投递是真异步的:"下游那行还没出现"是合法的中间态,
        // 不是失败。而 untilAsserted 默认只吞 AssertionError,orElseThrow 这类
        // NoSuchElementException 会直接炸穿等待循环。让它一并重试到超时为止。
        Awaitility.ignoreExceptionsByDefault();
    }

    private static final java.util.Set<String> CREATED =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private static synchronized void ensureDatabase(String database) {
        if (!CREATED.add(database)) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(
                     PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        } catch (SQLException e) {
            throw new IllegalStateException("建隔离库失败: " + database, e);
        }
    }

    /**
     * 环境修正:Spring 只会发现直接声明在测试类(或其外层类)上的 {@code @DynamicPropertySource}
     * 方法,声明在 {@code @Import} 进来的 {@code @TestConfiguration} 类里会被静默忽略。
     * 因此这里只提供可复用的注册逻辑,每个测试类需自带一个 {@code @DynamicPropertySource}
     * 方法调用本方法(见 SchemaIsolationTests 等)。
     */
    /** 二十一个域全在同一个容器里,靠 schema + 独立受限用户隔离。 */
    private static final List<String> DOMAINS = List.of(
            "identity", "org", "job", "engagement", "settlement", "fund", "broker", "profile",
            "matching", "review", "agreement", "voice", "talent", "notification", "content",
            "collab", "reimbursement", "mall", "ops", "reporting", "attendance");

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
        registerIsolatedProperties(registry, "shared");
    }

    /**
     * 每个需要独占的测试类给一个**自己的**库名。
     *
     * <p>共用一个隔离库是不够的:中继间隔现在按域配置,某个类只关掉自己那一个域的中继,
     * 其余十个域的中继照样以 200ms 轮询同一个库,照样会去动别的类的 outbox 行。
     */
    public static void registerIsolatedProperties(DynamicPropertyRegistry registry, String name) {
        registerProperties(registry, isolatedJdbcUrl(ISOLATED_DB + "_" + name));
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

    private static String isolatedJdbcUrl(String database) {
        ensureDatabase(database);
        String url = PG.getJdbcUrl();
        int dbStart = url.lastIndexOf('/') + 1;
        int dbEnd = url.indexOf('?', dbStart);
        return url.substring(0, dbStart) + database + (dbEnd < 0 ? "" : url.substring(dbEnd));
    }

    /*
     * **这里曾经有一个 @Bean 把容器暴露给 Spring。已经删掉。**
     *
     * 容器是整个测试套件共享的静态单例(上面的 static 块启动一次),而属性注册
     * (registerProperties)直接读静态字段 PG,**从来不需要这个 bean** ——
     * 它存在的唯一后果,就是把容器交给 Spring 管生命周期。
     *
     * 原来写了 @Bean(destroyMethod = "") 想挡住销毁,注释里也解释了为什么。
     * **但那个注解挡不住 spring-boot-testcontainers** —— 它对所有 Startable 类型的 bean
     * 有独立的生命周期接管,和 destroyMethod 无关。
     * 于是 Spring 测试上下文缓存一旦 LRU 淘汰,就会连带把全局共享的容器**停掉并删除**。
     *
     * 症状是 "FATAL: terminating connection due to unexpected postmaster exit",
     * 看着像数据库崩了。2026-08-05 为此走了四个错误方向
     * (调 max_connections、调每域池大小、调缓存上限、改成每类独立 JVM),
     * 全都只是在改"淘汰什么时候发生",没有一个碰到真正的原因。
     *
     * 定案靠的是**边跑边监视容器**:时间线是 运行中=1 → 运行中=0 且 已退出=0,
     * 即容器不是崩溃退出,是**被删除**了 —— 这才把矛头指向生命周期而不是资源。
     *
     * 证据链(四条全部吻合):
     *   缓存 64 → 98 个测试类,第 79 个崩   (淘汰从第 65 个开始)
     *   缓存 6  → 几乎立刻崩,383 个错      (淘汰立刻开始)
     *   每类独立 JVM → 崩溃 0 次           (每个 JVM 上下文少,不触发淘汰)
     *   手工开 800 条连接 → 内存 58MB,毫无压力(连接数从来不是原因)
     */
}
