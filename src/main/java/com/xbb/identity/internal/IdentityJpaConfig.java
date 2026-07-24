package com.xbb.identity.internal;

import com.xbb.FlywayProps;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.Map;

@Configuration
@EnableJpaRepositories(
        basePackages = "com.xbb.identity.internal",
        entityManagerFactoryRef = "identityEntityManagerFactory",
        transactionManagerRef = "identityTransactionManager")
public class IdentityJpaConfig {

    @Bean
    @ConfigurationProperties("xbb.domains.identity.datasource")
    DataSourceProperties identityDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    DataSource identityDataSource() {
        // 池子调小:测试里每个不同 @Import 组合的 @SpringBootTest 类都会被 Spring
        // 测试上下文缓存成独立的 ApplicationContext,各自常驻一份连接池;默认
        // 10 个连接 × 2 个域 × 好几个缓存的上下文,很容易把 Testcontainers 里
        // postgres:16-alpine 的 max_connections 打满(实测触发过)。
        HikariDataSource ds = identityDataSourceProperties()
                .initializeDataSourceBuilder().type(HikariDataSource.class).build();
        ds.setMaximumPoolSize(3);
        return ds;
    }

    @Bean
    @ConfigurationProperties("xbb.domains.identity.flyway")
    FlywayProps identityFlywayProps() {
        return new FlywayProps();
    }

    @Bean(initMethod = "migrate")
    Flyway identityFlyway() {
        FlywayProps p = identityFlywayProps();
        return Flyway.configure()
                .dataSource(p.getUrl(), p.getUser(), p.getPassword())
                .schemas(p.getSchemas().split(","))
                .locations(p.getLocations())
                .load();
    }

    @Bean(name = "identityFlywayInitializer")
    FlywayMigrationInitializer identityFlywayInitializer() {
        return new FlywayMigrationInitializer(identityFlyway(), null);
    }

    @Bean(name = "identityEntityManagerFactory")
    LocalContainerEntityManagerFactoryBean identityEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("identityFlywayInitializer") FlywayMigrationInitializer identityFlywayInitializer) {
        return builder.dataSource(identityDataSource())
                .packages("com.xbb.identity.internal")
                .persistenceUnit("identity")
                .properties(Map.of("hibernate.default_schema", "identity"))
                .build();
    }

    @Bean
    PlatformTransactionManager identityTransactionManager(
            @Qualifier("identityEntityManagerFactory") LocalContainerEntityManagerFactoryBean identityEntityManagerFactory) {
        return new JpaTransactionManager(identityEntityManagerFactory.getObject());
    }
}
