package com.xbb.review.internal;

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
        basePackages = "com.xbb.review.internal",
        entityManagerFactoryRef = "reviewEntityManagerFactory",
        transactionManagerRef = "reviewTransactionManager")
public class ReviewJpaConfig {

    @Bean
    DataSource reviewDataSource(Environment env) {
        return DomainJpaSupport.dataSource(env, "review");
    }

    @Bean(name = "reviewFlyway", initMethod = "migrate")
    Flyway reviewFlyway(Environment env) {
        return DomainJpaSupport.flyway(env, "review");
    }

    @Bean(name = "reviewFlywayInitializer")
    FlywayMigrationInitializer reviewFlywayInitializer(@Qualifier("reviewFlyway") Flyway reviewFlyway) {
        return new FlywayMigrationInitializer(reviewFlyway, null);
    }

    @Bean(name = "reviewEntityManagerFactory")
    LocalContainerEntityManagerFactoryBean reviewEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("reviewDataSource") DataSource reviewDataSource,
            @Qualifier("reviewFlywayInitializer") FlywayMigrationInitializer reviewFlywayInitializer) {
        return DomainJpaSupport.entityManagerFactory(builder, reviewDataSource, "review", "com.xbb.review.internal");
    }

    @Bean
    PlatformTransactionManager reviewTransactionManager(
            @Qualifier("reviewEntityManagerFactory") LocalContainerEntityManagerFactoryBean reviewEntityManagerFactory) {
        return new JpaTransactionManager(reviewEntityManagerFactory.getObject());
    }
}
