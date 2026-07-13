package com.xbb.baojing.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    private final CurrentUserArgumentResolver currentUserArgumentResolver;
    private final AppProperties appProperties;

    public WebMvcConfig(CurrentUserArgumentResolver currentUserArgumentResolver, AppProperties appProperties) {
        this.currentUserArgumentResolver = currentUserArgumentResolver;
        this.appProperties = appProperties;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserArgumentResolver);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String origins = appProperties.getCorsOrigins();
        var mapping = registry.addMapping("/api/**").allowedMethods("*").allowedHeaders("*");
        if ("*".equals(origins)) {
            mapping.allowedOriginPatterns("*");
        } else {
            mapping.allowedOrigins(origins.split(","));
            mapping.allowCredentials(true);
        }
    }
}
