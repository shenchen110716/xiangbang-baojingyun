package com.xbb.reporting.internal;

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
        basePackages = "com.xbb.reporting.internal",
        entityManagerFactoryRef = "reportingEntityManagerFactory",
        transactionManagerRef = "reportingTransactionManager")
public class ReportingJpaConfig {

    @Bean
    DataSource reportingDataSource(Environment env) {
        return DomainJpaSupport.dataSource(env, "reporting");
    }

    @Bean(name = "reportingFlyway", initMethod = "migrate")
    Flyway reportingFlyway(Environment env) {
        return DomainJpaSupport.flyway(env, "reporting");
    }

    @Bean(name = "reportingFlywayInitializer")
    FlywayMigrationInitializer reportingFlywayInitializer(@Qualifier("reportingFlyway") Flyway reportingFlyway) {
        return new FlywayMigrationInitializer(reportingFlyway, null);
    }

    @Bean(name = "reportingEntityManagerFactory")
    LocalContainerEntityManagerFactoryBean reportingEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("reportingDataSource") DataSource reportingDataSource,
            @Qualifier("reportingFlywayInitializer") FlywayMigrationInitializer reportingFlywayInitializer) {
        return DomainJpaSupport.entityManagerFactory(builder, reportingDataSource, "reporting", "com.xbb.reporting.internal");
    }

    @Bean
    PlatformTransactionManager reportingTransactionManager(
            @Qualifier("reportingEntityManagerFactory") LocalContainerEntityManagerFactoryBean reportingEntityManagerFactory) {
        return new JpaTransactionManager(reportingEntityManagerFactory.getObject());
    }
}
