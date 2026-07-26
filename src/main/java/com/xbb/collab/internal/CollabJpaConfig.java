package com.xbb.collab.internal;

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
        basePackages = "com.xbb.collab.internal",
        entityManagerFactoryRef = "collabEntityManagerFactory",
        transactionManagerRef = "collabTransactionManager")
public class CollabJpaConfig {

    @Bean
    DataSource collabDataSource(Environment env) {
        return DomainJpaSupport.dataSource(env, "collab");
    }

    @Bean(name = "collabFlyway", initMethod = "migrate")
    Flyway collabFlyway(Environment env) {
        return DomainJpaSupport.flyway(env, "collab");
    }

    @Bean(name = "collabFlywayInitializer")
    FlywayMigrationInitializer collabFlywayInitializer(@Qualifier("collabFlyway") Flyway collabFlyway) {
        return new FlywayMigrationInitializer(collabFlyway, null);
    }

    @Bean(name = "collabEntityManagerFactory")
    LocalContainerEntityManagerFactoryBean collabEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("collabDataSource") DataSource collabDataSource,
            @Qualifier("collabFlywayInitializer") FlywayMigrationInitializer collabFlywayInitializer) {
        return DomainJpaSupport.entityManagerFactory(builder, collabDataSource, "collab", "com.xbb.collab.internal");
    }

    @Bean
    PlatformTransactionManager collabTransactionManager(
            @Qualifier("collabEntityManagerFactory") LocalContainerEntityManagerFactoryBean collabEntityManagerFactory) {
        return new JpaTransactionManager(collabEntityManagerFactory.getObject());
    }
}
