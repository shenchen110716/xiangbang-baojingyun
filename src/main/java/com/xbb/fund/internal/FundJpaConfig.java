package com.xbb.fund.internal;

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
        basePackages = "com.xbb.fund.internal",
        entityManagerFactoryRef = "fundEntityManagerFactory",
        transactionManagerRef = "fundTransactionManager")
public class FundJpaConfig {

    @Bean
    DataSource fundDataSource(Environment env) {
        return DomainJpaSupport.dataSource(env, "fund");
    }

    @Bean(name = "fundFlyway", initMethod = "migrate")
    Flyway fundFlyway(Environment env) {
        return DomainJpaSupport.flyway(env, "fund");
    }

    @Bean(name = "fundFlywayInitializer")
    FlywayMigrationInitializer fundFlywayInitializer(@Qualifier("fundFlyway") Flyway fundFlyway) {
        return new FlywayMigrationInitializer(fundFlyway, null);
    }

    @Bean(name = "fundEntityManagerFactory")
    LocalContainerEntityManagerFactoryBean fundEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("fundDataSource") DataSource fundDataSource,
            @Qualifier("fundFlywayInitializer") FlywayMigrationInitializer fundFlywayInitializer) {
        return DomainJpaSupport.entityManagerFactory(builder, fundDataSource, "fund", "com.xbb.fund.internal");
    }

    @Bean
    PlatformTransactionManager fundTransactionManager(
            @Qualifier("fundEntityManagerFactory") LocalContainerEntityManagerFactoryBean fundEntityManagerFactory) {
        return new JpaTransactionManager(fundEntityManagerFactory.getObject());
    }
}
