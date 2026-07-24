package com.xbb.broker.internal;

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
        basePackages = "com.xbb.broker.internal",
        entityManagerFactoryRef = "brokerEntityManagerFactory",
        transactionManagerRef = "brokerTransactionManager")
public class BrokerJpaConfig {

    @Bean
    DataSource brokerDataSource(Environment env) {
        return DomainJpaSupport.dataSource(env, "broker");
    }

    @Bean(name = "brokerFlyway", initMethod = "migrate")
    Flyway brokerFlyway(Environment env) {
        return DomainJpaSupport.flyway(env, "broker");
    }

    @Bean(name = "brokerFlywayInitializer")
    FlywayMigrationInitializer brokerFlywayInitializer(@Qualifier("brokerFlyway") Flyway brokerFlyway) {
        return new FlywayMigrationInitializer(brokerFlyway, null);
    }

    @Bean(name = "brokerEntityManagerFactory")
    LocalContainerEntityManagerFactoryBean brokerEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("brokerDataSource") DataSource brokerDataSource,
            @Qualifier("brokerFlywayInitializer") FlywayMigrationInitializer brokerFlywayInitializer) {
        return DomainJpaSupport.entityManagerFactory(builder, brokerDataSource, "broker", "com.xbb.broker.internal");
    }

    @Bean
    PlatformTransactionManager brokerTransactionManager(
            @Qualifier("brokerEntityManagerFactory") LocalContainerEntityManagerFactoryBean brokerEntityManagerFactory) {
        return new JpaTransactionManager(brokerEntityManagerFactory.getObject());
    }
}
