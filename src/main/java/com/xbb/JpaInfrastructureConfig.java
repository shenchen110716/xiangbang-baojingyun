package com.xbb;

import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.JpaVendorAdapter;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import java.util.Map;

/**
 * 每域独立 DataSource/EntityManagerFactory 意味着 Spring Boot 的
 * JPA 自动配置(要求单一 DataSource 候选)会整体退避,{@link EntityManagerFactoryBuilder}
 * 也就没人提供了 —— 这里手动补上,给两个域的 *JpaConfig 共用。
 */
@Configuration
public class JpaInfrastructureConfig {

    @Bean
    JpaVendorAdapter jpaVendorAdapter() {
        return new HibernateJpaVendorAdapter();
    }

    @Bean
    EntityManagerFactoryBuilder entityManagerFactoryBuilder(JpaVendorAdapter jpaVendorAdapter) {
        // Boot 的 JPA 自动配置在多 DataSource 场景下整体退避,连带默认的驼峰->下划线
        // 命名策略也没了 —— 这里手动补回来,否则 createdAt 字段会去找字面量同名列。
        return new EntityManagerFactoryBuilder(jpaVendorAdapter, Map.of(
                "hibernate.hbm2ddl.auto", "validate",
                "hibernate.physical_naming_strategy",
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
                "hibernate.implicit_naming_strategy",
                "org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy"
        ), null);
    }
}
