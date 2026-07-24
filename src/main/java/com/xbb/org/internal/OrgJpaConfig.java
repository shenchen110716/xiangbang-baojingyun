package com.xbb.org.internal;

import com.xbb.DomainJpaSupport;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Configuration
@EnableJpaRepositories(
        basePackages = "com.xbb.org.internal",
        entityManagerFactoryRef = "orgEntityManagerFactory",
        transactionManagerRef = "orgTransactionManager")
public class OrgJpaConfig {

    @Bean
    DataSource orgDataSource(Environment env) {
        return DomainJpaSupport.dataSource(env, "org");
    }

    @Bean(name = "orgFlyway", initMethod = "migrate")
    Flyway orgFlyway(Environment env) {
        return DomainJpaSupport.flyway(env, "org");
    }

    @Bean(name = "orgFlywayInitializer")
    FlywayMigrationInitializer orgFlywayInitializer(@Qualifier("orgFlyway") Flyway orgFlyway) {
        return new FlywayMigrationInitializer(orgFlyway, null);
    }

    @Bean(name = "orgEntityManagerFactory")
    LocalContainerEntityManagerFactoryBean orgEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("orgDataSource") DataSource orgDataSource,
            @Qualifier("orgFlywayInitializer") FlywayMigrationInitializer orgFlywayInitializer) {
        return DomainJpaSupport.entityManagerFactory(builder, orgDataSource, "org", "com.xbb.org.internal");
    }

    @Bean
    PlatformTransactionManager orgTransactionManager(
            @Qualifier("orgEntityManagerFactory") LocalContainerEntityManagerFactoryBean orgEntityManagerFactory) {
        return new JpaTransactionManager(orgEntityManagerFactory.getObject());
    }
}
