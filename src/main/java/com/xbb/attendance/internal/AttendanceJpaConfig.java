package com.xbb.attendance.internal;

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
        basePackages = "com.xbb.attendance.internal",
        entityManagerFactoryRef = "attendanceEntityManagerFactory",
        transactionManagerRef = "attendanceTransactionManager")
public class AttendanceJpaConfig {

    @Bean
    DataSource attendanceDataSource(Environment env) {
        return DomainJpaSupport.dataSource(env, "attendance");
    }

    @Bean(name = "attendanceFlyway", initMethod = "migrate")
    Flyway attendanceFlyway(Environment env) {
        return DomainJpaSupport.flyway(env, "attendance");
    }

    @Bean(name = "attendanceFlywayInitializer")
    FlywayMigrationInitializer attendanceFlywayInitializer(@Qualifier("attendanceFlyway") Flyway attendanceFlyway) {
        return new FlywayMigrationInitializer(attendanceFlyway, null);
    }

    @Bean(name = "attendanceEntityManagerFactory")
    LocalContainerEntityManagerFactoryBean attendanceEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("attendanceDataSource") DataSource attendanceDataSource,
            @Qualifier("attendanceFlywayInitializer") FlywayMigrationInitializer attendanceFlywayInitializer) {
        return DomainJpaSupport.entityManagerFactory(builder, attendanceDataSource, "attendance",
                "com.xbb.attendance.internal");
    }

    @Bean
    PlatformTransactionManager attendanceTransactionManager(
            @Qualifier("attendanceEntityManagerFactory") LocalContainerEntityManagerFactoryBean attendanceEntityManagerFactory) {
        return new JpaTransactionManager(attendanceEntityManagerFactory.getObject());
    }
}
