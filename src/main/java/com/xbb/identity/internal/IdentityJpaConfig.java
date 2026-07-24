package com.xbb.identity.internal;

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
        basePackages = "com.xbb.identity.internal",
        entityManagerFactoryRef = "identityEntityManagerFactory",
        transactionManagerRef = "identityTransactionManager")
public class IdentityJpaConfig {

    @Bean
    DataSource identityDataSource(Environment env) {
        return DomainJpaSupport.dataSource(env, "identity");
    }

    @Bean(name = "identityFlyway", initMethod = "migrate")
    Flyway identityFlyway(Environment env) {
        return DomainJpaSupport.flyway(env, "identity");
    }

    @Bean(name = "identityFlywayInitializer")
    FlywayMigrationInitializer identityFlywayInitializer(@Qualifier("identityFlyway") Flyway identityFlyway) {
        return new FlywayMigrationInitializer(identityFlyway, null);
    }

    @Bean(name = "identityEntityManagerFactory")
    LocalContainerEntityManagerFactoryBean identityEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("identityDataSource") DataSource identityDataSource,
            @Qualifier("identityFlywayInitializer") FlywayMigrationInitializer identityFlywayInitializer) {
        return DomainJpaSupport.entityManagerFactory(builder, identityDataSource, "identity", "com.xbb.identity.internal");
    }

    @Bean
    PlatformTransactionManager identityTransactionManager(
            @Qualifier("identityEntityManagerFactory") LocalContainerEntityManagerFactoryBean identityEntityManagerFactory) {
        return new JpaTransactionManager(identityEntityManagerFactory.getObject());
    }
}
