package com.xbb.agreement.internal;

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
        basePackages = "com.xbb.agreement.internal",
        entityManagerFactoryRef = "agreementEntityManagerFactory",
        transactionManagerRef = "agreementTransactionManager")
public class AgreementJpaConfig {

    @Bean
    DataSource agreementDataSource(Environment env) {
        return DomainJpaSupport.dataSource(env, "agreement");
    }

    @Bean(name = "agreementFlyway", initMethod = "migrate")
    Flyway agreementFlyway(Environment env) {
        return DomainJpaSupport.flyway(env, "agreement");
    }

    @Bean(name = "agreementFlywayInitializer")
    FlywayMigrationInitializer agreementFlywayInitializer(@Qualifier("agreementFlyway") Flyway agreementFlyway) {
        return new FlywayMigrationInitializer(agreementFlyway, null);
    }

    @Bean(name = "agreementEntityManagerFactory")
    LocalContainerEntityManagerFactoryBean agreementEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("agreementDataSource") DataSource agreementDataSource,
            @Qualifier("agreementFlywayInitializer") FlywayMigrationInitializer agreementFlywayInitializer) {
        return DomainJpaSupport.entityManagerFactory(builder, agreementDataSource, "agreement", "com.xbb.agreement.internal");
    }

    @Bean
    PlatformTransactionManager agreementTransactionManager(
            @Qualifier("agreementEntityManagerFactory") LocalContainerEntityManagerFactoryBean agreementEntityManagerFactory) {
        return new JpaTransactionManager(agreementEntityManagerFactory.getObject());
    }
}
