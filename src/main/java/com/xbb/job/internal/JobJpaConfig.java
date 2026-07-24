package com.xbb.job.internal;

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
        basePackages = "com.xbb.job.internal",
        entityManagerFactoryRef = "jobEntityManagerFactory",
        transactionManagerRef = "jobTransactionManager")
public class JobJpaConfig {

    @Bean
    DataSource jobDataSource(Environment env) {
        return DomainJpaSupport.dataSource(env, "job");
    }

    @Bean(name = "jobFlyway", initMethod = "migrate")
    Flyway jobFlyway(Environment env) {
        return DomainJpaSupport.flyway(env, "job");
    }

    @Bean(name = "jobFlywayInitializer")
    FlywayMigrationInitializer jobFlywayInitializer(@Qualifier("jobFlyway") Flyway jobFlyway) {
        return new FlywayMigrationInitializer(jobFlyway, null);
    }

    @Bean(name = "jobEntityManagerFactory")
    LocalContainerEntityManagerFactoryBean jobEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("jobDataSource") DataSource jobDataSource,
            @Qualifier("jobFlywayInitializer") FlywayMigrationInitializer jobFlywayInitializer) {
        return DomainJpaSupport.entityManagerFactory(builder, jobDataSource, "job", "com.xbb.job.internal");
    }

    @Bean
    PlatformTransactionManager jobTransactionManager(
            @Qualifier("jobEntityManagerFactory") LocalContainerEntityManagerFactoryBean jobEntityManagerFactory) {
        return new JpaTransactionManager(jobEntityManagerFactory.getObject());
    }
}
