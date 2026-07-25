package com.xbb.engagement.internal;

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
        basePackages = "com.xbb.engagement.internal",
        entityManagerFactoryRef = "engagementEntityManagerFactory",
        transactionManagerRef = "engagementTransactionManager")
public class EngagementJpaConfig {

    @Bean
    DataSource engagementDataSource(Environment env) {
        return DomainJpaSupport.dataSource(env, "engagement");
    }

    @Bean(name = "engagementFlyway", initMethod = "migrate")
    Flyway engagementFlyway(Environment env) {
        return DomainJpaSupport.flyway(env, "engagement");
    }

    @Bean(name = "engagementFlywayInitializer")
    FlywayMigrationInitializer engagementFlywayInitializer(@Qualifier("engagementFlyway") Flyway engagementFlyway) {
        return new FlywayMigrationInitializer(engagementFlyway, null);
    }

    @Bean(name = "engagementEntityManagerFactory")
    LocalContainerEntityManagerFactoryBean engagementEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("engagementDataSource") DataSource engagementDataSource,
            @Qualifier("engagementFlywayInitializer") FlywayMigrationInitializer engagementFlywayInitializer) {
        return DomainJpaSupport.entityManagerFactory(builder, engagementDataSource, "engagement", "com.xbb.engagement.internal");
    }

    @Bean
    PlatformTransactionManager engagementTransactionManager(
            @Qualifier("engagementEntityManagerFactory") LocalContainerEntityManagerFactoryBean engagementEntityManagerFactory) {
        return new JpaTransactionManager(engagementEntityManagerFactory.getObject());
    }
}
