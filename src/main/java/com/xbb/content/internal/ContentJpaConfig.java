package com.xbb.content.internal;

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
        basePackages = "com.xbb.content.internal",
        entityManagerFactoryRef = "contentEntityManagerFactory",
        transactionManagerRef = "contentTransactionManager")
public class ContentJpaConfig {

    @Bean
    DataSource contentDataSource(Environment env) {
        return DomainJpaSupport.dataSource(env, "content");
    }

    @Bean(name = "contentFlyway", initMethod = "migrate")
    Flyway contentFlyway(Environment env) {
        return DomainJpaSupport.flyway(env, "content");
    }

    @Bean(name = "contentFlywayInitializer")
    FlywayMigrationInitializer contentFlywayInitializer(@Qualifier("contentFlyway") Flyway contentFlyway) {
        return new FlywayMigrationInitializer(contentFlyway, null);
    }

    @Bean(name = "contentEntityManagerFactory")
    LocalContainerEntityManagerFactoryBean contentEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("contentDataSource") DataSource contentDataSource,
            @Qualifier("contentFlywayInitializer") FlywayMigrationInitializer contentFlywayInitializer) {
        return DomainJpaSupport.entityManagerFactory(builder, contentDataSource, "content", "com.xbb.content.internal");
    }

    @Bean
    PlatformTransactionManager contentTransactionManager(
            @Qualifier("contentEntityManagerFactory") LocalContainerEntityManagerFactoryBean contentEntityManagerFactory) {
        return new JpaTransactionManager(contentEntityManagerFactory.getObject());
    }
}
