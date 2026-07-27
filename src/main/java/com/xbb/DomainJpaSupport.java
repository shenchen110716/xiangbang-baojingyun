package com.xbb;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.core.env.Environment;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

import javax.sql.DataSource;
import java.util.Map;

/**
 * 三个域(identity/org/job)的 DataSource/Flyway/EntityManagerFactory 装配逻辑
 * 完全一致,只有 domain 名字和扫描包不同——收进这里,避免每加一个域就
 * 复制一份 IdentityJpaConfig/OrgJpaConfig 那样的 75 行样板。
 * 不是 @Configuration:@EnableJpaRepositories 的属性必须是字面量,
 * 没法参数化,所以每个域仍需要自己的薄 *JpaConfig 类持有那个注解,
 * 但类体内容全部委托到这里。
 */
public final class DomainJpaSupport {

    private DomainJpaSupport() { }

    public static DataSource dataSource(Environment env, String domain) {
        DataSourceProperties props = Binder.get(env)
                .bind("xbb.domains." + domain + ".datasource", Bindable.of(DataSourceProperties.class))
                .orElseThrow(() -> new IllegalStateException("missing xbb.domains." + domain + " configuration"));
        HikariDataSource ds = props.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        // **可配置,不再写死。** 这个值原来是硬编码的 3——那是为测试选的
        // (每个 @SpringBootTest 类一个 ApplicationContext,每个上下文 × 每个域 = 一个池子,
        // 30 多个测试类 × 20 个域 = 600+ 个池子同时存在),但生产用的是同一个值。
        //
        // 生产侧的算术:20 个域 × 每域上限 = 单实例连接上限。取 3 就是 60,
        // PostgreSQL 默认 max_connections = 100,**两个实例就超**——水平扩容被堵死,
        // 而这件事没写在任何部署文档里。现在能按环境调,并在启动时把算术打出来。
        int maxPerDomain = env.getProperty("xbb.datasource.max-pool-size-per-domain", Integer.class, 3);
        ds.setMaximumPoolSize(maxPerDomain);
        // 关键:Hikari 默认 minimumIdle = maximumPoolSize,意味着**每个池子都会长期
        // 占住 3 条连接**,哪怕整个测试类根本没碰这个域。300 个池子就是 900 条常驻连接,
        // 这才是"每加一个域就要调大 max_connections"的真正原因。
        // 设成 0 + 30 秒空闲回收后,池子只在真正用到时才建连接,用完就还——
        // 需求从"域数 × 上下文数 × 3"降到"实际并发用到的那几个域"。
        ds.setMinimumIdle(0);
        ds.setIdleTimeout(30_000);
        ds.setPoolName("xbb-" + domain);
        return ds;
    }

    public static Flyway flyway(Environment env, String domain) {
        FlywayProps p = Binder.get(env)
                .bind("xbb.domains." + domain + ".flyway", Bindable.of(FlywayProps.class))
                .orElseThrow(() -> new IllegalStateException("missing xbb.domains." + domain + " configuration"));
        return Flyway.configure()
                .dataSource(p.getUrl(), p.getUser(), p.getPassword())
                .schemas(p.getSchemas().split(","))
                .locations(p.getLocations())
                .load();
    }

    public static LocalContainerEntityManagerFactoryBean entityManagerFactory(
            EntityManagerFactoryBuilder builder, DataSource dataSource, String domain, String packageToScan) {
        return builder.dataSource(dataSource)
                .packages(packageToScan)
                .persistenceUnit(domain)
                .properties(Map.of("hibernate.default_schema", domain))
                .build();
    }
}
