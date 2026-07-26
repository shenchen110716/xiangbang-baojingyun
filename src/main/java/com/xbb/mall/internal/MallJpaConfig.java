package com.xbb.mall.internal;

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
        basePackages = "com.xbb.mall.internal",
        entityManagerFactoryRef = "mallEntityManagerFactory",
        transactionManagerRef = "mallTransactionManager")
public class MallJpaConfig {

    @Bean
    DataSource mallDataSource(Environment env) {
        return DomainJpaSupport.dataSource(env, "mall");
    }

    @Bean(name = "mallFlyway", initMethod = "migrate")
    Flyway mallFlyway(Environment env) {
        return DomainJpaSupport.flyway(env, "mall");
    }

    @Bean(name = "mallFlywayInitializer")
    FlywayMigrationInitializer mallFlywayInitializer(@Qualifier("mallFlyway") Flyway mallFlyway) {
        return new FlywayMigrationInitializer(mallFlyway, null);
    }

    @Bean(name = "mallEntityManagerFactory")
    LocalContainerEntityManagerFactoryBean mallEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("mallDataSource") DataSource mallDataSource,
            @Qualifier("mallFlywayInitializer") FlywayMigrationInitializer mallFlywayInitializer) {
        return DomainJpaSupport.entityManagerFactory(builder, mallDataSource, "mall", "com.xbb.mall.internal");
    }

    @Bean
    PlatformTransactionManager mallTransactionManager(
            @Qualifier("mallEntityManagerFactory") LocalContainerEntityManagerFactoryBean mallEntityManagerFactory) {
        return new JpaTransactionManager(mallEntityManagerFactory.getObject());
    }
}
