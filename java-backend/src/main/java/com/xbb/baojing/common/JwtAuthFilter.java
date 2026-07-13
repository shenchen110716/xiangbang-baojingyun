package com.xbb.baojing.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Ports backend/core/security.py's current_user() dependency: extract Bearer
 * token, decode, load the user, and check active/session-version/role — but
 * as a filter that only *records* the outcome. Whether a 401/403 actually
 * fires depends on whether the controller method has a User parameter (see
 * CurrentUserArgumentResolver), same as current_user() being an opt-in
 * FastAPI dependency rather than a blanket gate. */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final UserMapper userMapper;

    public JwtAuthFilter(JwtService jwtService, UserMapper userMapper) {
        this.jwtService = jwtService;
        this.userMapper = userMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            String header = request.getHeader("Authorization");
            if (header == null || !header.startsWith("Bearer ")) {
                CurrentUserContext.set(CurrentUserContext.AuthResult.fail(401, "请先登录"));
            } else {
                String token = header.substring("Bearer ".length());
                int[] claims = jwtService.verify(token);
                if (claims == null) {
                    CurrentUserContext.set(CurrentUserContext.AuthResult.fail(401, "登录已过期"));
                } else {
                    User user = userMapper.findById(claims[0]);
                    if (user == null || !user.isActive()) {
                        CurrentUserContext.set(CurrentUserContext.AuthResult.fail(401, "用户无效"));
                    } else if (claims[1] != user.getSessionVersion()) {
                        CurrentUserContext.set(CurrentUserContext.AuthResult.fail(401, "登录已过期，请重新登录"));
                    } else if (!user.getRole().equals("admin") && !user.getRole().equals("enterprise")) {
                        CurrentUserContext.set(CurrentUserContext.AuthResult.fail(403, "该账号暂未开通管理端权限"));
                    } else {
                        CurrentUserContext.set(CurrentUserContext.AuthResult.ok(user));
                    }
                }
            }
            chain.doFilter(request, response);
        } finally {
            CurrentUserContext.clear();
        }
    }
}
