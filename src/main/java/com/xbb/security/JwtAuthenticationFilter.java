package com.xbb.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 校验 Authorization: Bearer <jwt>,通过则把 {@link AuthenticatedUser} 放进
 * SecurityContext——后续 controller 用 @AuthenticationPrincipal 拿到的是校验过的
 * 调用者身份,而不是请求体里客户端自己填的 userId(那是 IDOR 的根源)。
 * token 缺失/无效时不拒绝请求,交给 SecurityConfig 的 authorizeHttpRequests 决定
 * 该端点是否要求已认证。
 */
class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwt;

    JwtAuthenticationFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                long userId = jwt.parseUserId(header.substring(7));
                var authentication = new UsernamePasswordAuthenticationToken(
                        new AuthenticatedUser(userId), null, List.of());
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (RuntimeException ignored) {
                // 签名无效/已过期:保持未认证,由授权规则决定该端点是否放行
            }
        }
        chain.doFilter(request, response);
    }
}
