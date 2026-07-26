package com.xbb.reimbursement.internal;

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
        basePackages = "com.xbb.reimbursement.internal",
        entityManagerFactoryRef = "reimbursementEntityManagerFactory",
        transactionManagerRef = "reimbursementTransactionManager")
public class ReimbursementJpaConfig {

    @Bean
    DataSource reimbursementDataSource(Environment env) {
        return DomainJpaSupport.dataSource(env, "reimbursement");
    }

    @Bean(name = "reimbursementFlyway", initMethod = "migrate")
    Flyway reimbursementFlyway(Environment env) {
        return DomainJpaSupport.flyway(env, "reimbursement");
    }

    @Bean(name = "reimbursementFlywayInitializer")
    FlywayMigrationInitializer reimbursementFlywayInitializer(@Qualifier("reimbursementFlyway") Flyway reimbursementFlyway) {
        return new FlywayMigrationInitializer(reimbursementFlyway, null);
    }

    @Bean(name = "reimbursementEntityManagerFactory")
    LocalContainerEntityManagerFactoryBean reimbursementEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("reimbursementDataSource") DataSource reimbursementDataSource,
            @Qualifier("reimbursementFlywayInitializer") FlywayMigrationInitializer reimbursementFlywayInitializer) {
        return DomainJpaSupport.entityManagerFactory(builder, reimbursementDataSource, "reimbursement", "com.xbb.reimbursement.internal");
    }

    @Bean
    PlatformTransactionManager reimbursementTransactionManager(
            @Qualifier("reimbursementEntityManagerFactory") LocalContainerEntityManagerFactoryBean reimbursementEntityManagerFactory) {
        return new JpaTransactionManager(reimbursementEntityManagerFactory.getObject());
    }
}
