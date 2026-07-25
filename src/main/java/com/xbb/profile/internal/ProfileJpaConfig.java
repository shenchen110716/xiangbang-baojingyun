package com.xbb.profile.internal;

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
        basePackages = "com.xbb.profile.internal",
        entityManagerFactoryRef = "profileEntityManagerFactory",
        transactionManagerRef = "profileTransactionManager")
public class ProfileJpaConfig {

    @Bean
    DataSource profileDataSource(Environment env) {
        return DomainJpaSupport.dataSource(env, "profile");
    }

    @Bean(name = "profileFlyway", initMethod = "migrate")
    Flyway profileFlyway(Environment env) {
        return DomainJpaSupport.flyway(env, "profile");
    }

    @Bean(name = "profileFlywayInitializer")
    FlywayMigrationInitializer profileFlywayInitializer(@Qualifier("profileFlyway") Flyway profileFlyway) {
        return new FlywayMigrationInitializer(profileFlyway, null);
    }

    @Bean(name = "profileEntityManagerFactory")
    LocalContainerEntityManagerFactoryBean profileEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("profileDataSource") DataSource profileDataSource,
            @Qualifier("profileFlywayInitializer") FlywayMigrationInitializer profileFlywayInitializer) {
        return DomainJpaSupport.entityManagerFactory(builder, profileDataSource, "profile", "com.xbb.profile.internal");
    }

    @Bean
    PlatformTransactionManager profileTransactionManager(
            @Qualifier("profileEntityManagerFactory") LocalContainerEntityManagerFactoryBean profileEntityManagerFactory) {
        return new JpaTransactionManager(profileEntityManagerFactory.getObject());
    }
}
