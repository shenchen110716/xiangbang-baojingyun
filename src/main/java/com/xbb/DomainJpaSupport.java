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
        // 见 xbb-v1-progress.md:测试里每个 @SpringBootTest 类都会被 Spring
        // 测试上下文缓存出独立的 ApplicationContext,连接池默认 10 很容易把
        // Testcontainers 的 max_connections 打满。
        ds.setMaximumPoolSize(3);
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
