package com.xbb.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.http.HttpStatus;

@Configuration
public class SecurityConfig {

    @Bean
    JwtAuthenticationFilter jwtAuthenticationFilter(JwtService jwt) {
        return new JwtAuthenticationFilter(jwt);
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // 拿 token 之前必须能免登录访问:请求验证码、拿验证码换 token
                .requestMatchers("/api/identity/code", "/api/identity/login").permitAll()
                // 健康检查要给编排/负载均衡探活用,必须免鉴权。
                // 只放行 health,**不放行 metrics** —— 那里会暴露内部结构。
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                // 其余全部端点(两个域)都要求带有效 JWT——谁调用由 token 决定,
                // 不再信任请求体里客户端自己填的 userId/legalRepUserId。
                .anyRequest().authenticated())
            // 默认没配 entry point 时 Spring Security 对未认证请求回 403;
            // REST API 语义上"没带/带错 token"应该是 401,交给调用方区分"没登录"和"没权限"。
            .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
