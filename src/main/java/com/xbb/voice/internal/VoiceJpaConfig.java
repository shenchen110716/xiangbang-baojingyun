package com.xbb.voice.internal;

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
        basePackages = "com.xbb.voice.internal",
        entityManagerFactoryRef = "voiceEntityManagerFactory",
        transactionManagerRef = "voiceTransactionManager")
public class VoiceJpaConfig {

    @Bean
    DataSource voiceDataSource(Environment env) {
        return DomainJpaSupport.dataSource(env, "voice");
    }

    @Bean(name = "voiceFlyway", initMethod = "migrate")
    Flyway voiceFlyway(Environment env) {
        return DomainJpaSupport.flyway(env, "voice");
    }

    @Bean(name = "voiceFlywayInitializer")
    FlywayMigrationInitializer voiceFlywayInitializer(@Qualifier("voiceFlyway") Flyway voiceFlyway) {
        return new FlywayMigrationInitializer(voiceFlyway, null);
    }

    @Bean(name = "voiceEntityManagerFactory")
    LocalContainerEntityManagerFactoryBean voiceEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("voiceDataSource") DataSource voiceDataSource,
            @Qualifier("voiceFlywayInitializer") FlywayMigrationInitializer voiceFlywayInitializer) {
        return DomainJpaSupport.entityManagerFactory(builder, voiceDataSource, "voice", "com.xbb.voice.internal");
    }

    @Bean
    PlatformTransactionManager voiceTransactionManager(
            @Qualifier("voiceEntityManagerFactory") LocalContainerEntityManagerFactoryBean voiceEntityManagerFactory) {
        return new JpaTransactionManager(voiceEntityManagerFactory.getObject());
    }
}
