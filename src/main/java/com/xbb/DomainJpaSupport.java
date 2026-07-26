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
        // 见 xbb-v1-progress.md:测试里每个 @SpringBootTest 类都会被 Spring 测试上下文
        // 缓存出独立的 ApplicationContext,每个上下文 × 每个域 = 一个连接池。
        // 10 个域 × 30 多个测试类 = 300+ 个池子同时存在。
        ds.setMaximumPoolSize(3);
        // 关键:Hikari 默认 minimumIdle = maximumPoolSize,意味着**每个池子都会长期
        // 占住 3 条连接**,哪怕整个测试类根本没碰这个域。300 个池子就是 900 条常驻连接,
        // 这才是"每加一个域就要调大 max_connections"的真正原因。
        // 设成 0 + 30 秒空闲回收后,池子只在真正用到时才建连接,用完就还——
        // 需求从"域数 × 上下文数 × 3"降到"实际并发用到的那几个域"。
        ds.setMinimumIdle(0);
        ds.setIdleTimeout(30_000);
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
