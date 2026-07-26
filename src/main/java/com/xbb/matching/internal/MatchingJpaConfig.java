package com.xbb.matching.internal;

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
        basePackages = "com.xbb.matching.internal",
        entityManagerFactoryRef = "matchingEntityManagerFactory",
        transactionManagerRef = "matchingTransactionManager")
public class MatchingJpaConfig {

    @Bean
    DataSource matchingDataSource(Environment env) {
        return DomainJpaSupport.dataSource(env, "matching");
    }

    @Bean(name = "matchingFlyway", initMethod = "migrate")
    Flyway matchingFlyway(Environment env) {
        return DomainJpaSupport.flyway(env, "matching");
    }

    @Bean(name = "matchingFlywayInitializer")
    FlywayMigrationInitializer matchingFlywayInitializer(@Qualifier("matchingFlyway") Flyway matchingFlyway) {
        return new FlywayMigrationInitializer(matchingFlyway, null);
    }

    @Bean(name = "matchingEntityManagerFactory")
    LocalContainerEntityManagerFactoryBean matchingEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("matchingDataSource") DataSource matchingDataSource,
            @Qualifier("matchingFlywayInitializer") FlywayMigrationInitializer matchingFlywayInitializer) {
        return DomainJpaSupport.entityManagerFactory(builder, matchingDataSource, "matching", "com.xbb.matching.internal");
    }

    @Bean
    PlatformTransactionManager matchingTransactionManager(
            @Qualifier("matchingEntityManagerFactory") LocalContainerEntityManagerFactoryBean matchingEntityManagerFactory) {
        return new JpaTransactionManager(matchingEntityManagerFactory.getObject());
    }
}
