package com.xbb.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.http.HttpMethod;
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
                // ERROR 分派放行。**这不是把 /error 端点对外开放** ——
                // 它只在容器内部转发时命中。不放行的话,任何未被 @ExceptionHandler
                // 截住的异常都会变成 401:JWT 过滤器不跑 ERROR 分派,请求在那一刻是未认证的。
                // 于是"字段填错"和"没登录"在前端看起来一模一样。
                .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                // 拿 token 之前必须能免登录访问:请求验证码、拿验证码换 token
                .requestMatchers("/api/identity/code", "/api/identity/login").permitAll()
                // 微信登录**必须匿名可达** —— 它是登录本身,要求先登录在逻辑上不成立。
                // 只放 login,**不放 bind**:绑定是把微信挂到"当前账号"上,
                // 放开的话任何人都能把自己的微信绑到别人的账号
                .requestMatchers("/api/identity/wechat/login").permitAll()
                // 取验证码的开发端点。这里放行只是让请求能到达控制器——
                // **真正的门在 DevCodeController 里**(要 X-Dev-Token,且只在 mock 模式装配)。
                // 放在这一层是因为它必须在登录之前可达,鉴权自然不能靠 JWT。
                .requestMatchers("/api/identity/dev/code").permitAll()
                // 前端静态资源。前端本身不含任何机密,所有数据都要带 token 现取;
                // 不放行的话打开首页就是 401,等于没有前端。
                .requestMatchers("/", "/index.html", "/favicon.svg", "/assets/**").permitAll()
                // 岗位浏览对未登录开放(老板 2026-08-06 拍板)。
                //
                // **为什么必须开。**求职端小程序第一屏就是岗位,而没绑过微信的新用户
                // 拿不到 token —— 后端对陌生 openid 不建账号(那条是特意设计的)。
                // 两条规则叠在一起,新用户第一屏永远是空的,还没看到任何东西就被挡在门外。
                //
                // **范围就这两个,而且只放 GET。**它们不收 caller,回的是
                // 岗位标题/薪资/单位名/地址,不含任何个人信息。
                // 写成 `/api/job/**` 的话 /mine 也会被放开 —— 它靠 caller 取数,
                // 匿名进去要么 500 要么把别人的岗位列出来;POST 更是直接能匿名发岗。
                // MiniprogramJobFeedTest 里逐条守着这条边界。
                .requestMatchers(HttpMethod.GET, "/api/job/open").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/job/{id:[0-9]+}").permitAll()
                // 行政区划字典。**公开,理由同上** —— 小程序发单页第一屏就要选地区,
                // 新用户拿不到 token,挡住的话他连地区都选不了。
                // 回的是省市名称和国标码,不含任何个人信息
                .requestMatchers(HttpMethod.GET, "/api/regions").permitAll()
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
