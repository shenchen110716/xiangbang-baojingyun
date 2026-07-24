package com.xbb.settlement.internal;

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
        basePackages = "com.xbb.settlement.internal",
        entityManagerFactoryRef = "settlementEntityManagerFactory",
        transactionManagerRef = "settlementTransactionManager")
public class SettlementJpaConfig {

    @Bean
    DataSource settlementDataSource(Environment env) {
        return DomainJpaSupport.dataSource(env, "settlement");
    }

    @Bean(name = "settlementFlyway", initMethod = "migrate")
    Flyway settlementFlyway(Environment env) {
        return DomainJpaSupport.flyway(env, "settlement");
    }

    @Bean(name = "settlementFlywayInitializer")
    FlywayMigrationInitializer settlementFlywayInitializer(@Qualifier("settlementFlyway") Flyway settlementFlyway) {
        return new FlywayMigrationInitializer(settlementFlyway, null);
    }

    @Bean(name = "settlementEntityManagerFactory")
    LocalContainerEntityManagerFactoryBean settlementEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("settlementDataSource") DataSource settlementDataSource,
            @Qualifier("settlementFlywayInitializer") FlywayMigrationInitializer settlementFlywayInitializer) {
        return DomainJpaSupport.entityManagerFactory(builder, settlementDataSource, "settlement", "com.xbb.settlement.internal");
    }

    @Bean
    PlatformTransactionManager settlementTransactionManager(
            @Qualifier("settlementEntityManagerFactory") LocalContainerEntityManagerFactoryBean settlementEntityManagerFactory) {
        return new JpaTransactionManager(settlementEntityManagerFactory.getObject());
    }
}
