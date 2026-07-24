package com.xbb.org.internal;

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
        basePackages = "com.xbb.org.internal",
        entityManagerFactoryRef = "orgEntityManagerFactory",
        transactionManagerRef = "orgTransactionManager")
public class OrgJpaConfig {

    @Bean
    @ConfigurationProperties("xbb.domains.org.datasource")
    DataSourceProperties orgDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    DataSource orgDataSource() {
        HikariDataSource ds = orgDataSourceProperties()
                .initializeDataSourceBuilder().type(HikariDataSource.class).build();
        ds.setMaximumPoolSize(3);
        return ds;
    }

    @Bean
    @ConfigurationProperties("xbb.domains.org.flyway")
    FlywayProps orgFlywayProps() {
        return new FlywayProps();
    }

    @Bean(initMethod = "migrate")
    Flyway orgFlyway() {
        FlywayProps p = orgFlywayProps();
        return Flyway.configure()
                .dataSource(p.getUrl(), p.getUser(), p.getPassword())
                .schemas(p.getSchemas().split(","))
                .locations(p.getLocations())
                .load();
    }

    @Bean(name = "orgFlywayInitializer")
    FlywayMigrationInitializer orgFlywayInitializer() {
        return new FlywayMigrationInitializer(orgFlyway(), null);
    }

    @Bean(name = "orgEntityManagerFactory")
    LocalContainerEntityManagerFactoryBean orgEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("orgFlywayInitializer") FlywayMigrationInitializer orgFlywayInitializer) {
        return builder.dataSource(orgDataSource())
                .packages("com.xbb.org.internal")
                .persistenceUnit("org")
                .properties(Map.of("hibernate.default_schema", "org"))
                .build();
    }

    @Bean
    PlatformTransactionManager orgTransactionManager(
            @Qualifier("orgEntityManagerFactory") LocalContainerEntityManagerFactoryBean orgEntityManagerFactory) {
        return new JpaTransactionManager(orgEntityManagerFactory.getObject());
    }
}
