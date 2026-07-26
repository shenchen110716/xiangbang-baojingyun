package com.xbb.ops.internal;

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
        basePackages = "com.xbb.ops.internal",
        entityManagerFactoryRef = "opsEntityManagerFactory",
        transactionManagerRef = "opsTransactionManager")
public class OpsJpaConfig {

    @Bean
    DataSource opsDataSource(Environment env) {
        return DomainJpaSupport.dataSource(env, "ops");
    }

    @Bean(name = "opsFlyway", initMethod = "migrate")
    Flyway opsFlyway(Environment env) {
        return DomainJpaSupport.flyway(env, "ops");
    }

    @Bean(name = "opsFlywayInitializer")
    FlywayMigrationInitializer opsFlywayInitializer(@Qualifier("opsFlyway") Flyway opsFlyway) {
        return new FlywayMigrationInitializer(opsFlyway, null);
    }

    @Bean(name = "opsEntityManagerFactory")
    LocalContainerEntityManagerFactoryBean opsEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("opsDataSource") DataSource opsDataSource,
            @Qualifier("opsFlywayInitializer") FlywayMigrationInitializer opsFlywayInitializer) {
        return DomainJpaSupport.entityManagerFactory(builder, opsDataSource, "ops", "com.xbb.ops.internal");
    }

    @Bean
    PlatformTransactionManager opsTransactionManager(
            @Qualifier("opsEntityManagerFactory") LocalContainerEntityManagerFactoryBean opsEntityManagerFactory) {
        return new JpaTransactionManager(opsEntityManagerFactory.getObject());
    }
}
