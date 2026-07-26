package com.xbb.notification.internal;

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
        basePackages = "com.xbb.notification.internal",
        entityManagerFactoryRef = "notificationEntityManagerFactory",
        transactionManagerRef = "notificationTransactionManager")
public class NotificationJpaConfig {

    @Bean
    DataSource notificationDataSource(Environment env) {
        return DomainJpaSupport.dataSource(env, "notification");
    }

    @Bean(name = "notificationFlyway", initMethod = "migrate")
    Flyway notificationFlyway(Environment env) {
        return DomainJpaSupport.flyway(env, "notification");
    }

    @Bean(name = "notificationFlywayInitializer")
    FlywayMigrationInitializer notificationFlywayInitializer(@Qualifier("notificationFlyway") Flyway notificationFlyway) {
        return new FlywayMigrationInitializer(notificationFlyway, null);
    }

    @Bean(name = "notificationEntityManagerFactory")
    LocalContainerEntityManagerFactoryBean notificationEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("notificationDataSource") DataSource notificationDataSource,
            @Qualifier("notificationFlywayInitializer") FlywayMigrationInitializer notificationFlywayInitializer) {
        return DomainJpaSupport.entityManagerFactory(builder, notificationDataSource, "notification", "com.xbb.notification.internal");
    }

    @Bean
    PlatformTransactionManager notificationTransactionManager(
            @Qualifier("notificationEntityManagerFactory") LocalContainerEntityManagerFactoryBean notificationEntityManagerFactory) {
        return new JpaTransactionManager(notificationEntityManagerFactory.getObject());
    }
}
