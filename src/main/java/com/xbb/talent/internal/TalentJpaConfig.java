package com.xbb.talent.internal;

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
        basePackages = "com.xbb.talent.internal",
        entityManagerFactoryRef = "talentEntityManagerFactory",
        transactionManagerRef = "talentTransactionManager")
public class TalentJpaConfig {

    @Bean
    DataSource talentDataSource(Environment env) {
        return DomainJpaSupport.dataSource(env, "talent");
    }

    @Bean(name = "talentFlyway", initMethod = "migrate")
    Flyway talentFlyway(Environment env) {
        return DomainJpaSupport.flyway(env, "talent");
    }

    @Bean(name = "talentFlywayInitializer")
    FlywayMigrationInitializer talentFlywayInitializer(@Qualifier("talentFlyway") Flyway talentFlyway) {
        return new FlywayMigrationInitializer(talentFlyway, null);
    }

    @Bean(name = "talentEntityManagerFactory")
    LocalContainerEntityManagerFactoryBean talentEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("talentDataSource") DataSource talentDataSource,
            @Qualifier("talentFlywayInitializer") FlywayMigrationInitializer talentFlywayInitializer) {
        return DomainJpaSupport.entityManagerFactory(builder, talentDataSource, "talent", "com.xbb.talent.internal");
    }

    @Bean
    PlatformTransactionManager talentTransactionManager(
            @Qualifier("talentEntityManagerFactory") LocalContainerEntityManagerFactoryBean talentEntityManagerFactory) {
        return new JpaTransactionManager(talentEntityManagerFactory.getObject());
    }
}
