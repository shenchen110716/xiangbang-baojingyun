package com.xbb.baojing.common;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Ports backend/core/config.py's _check_production_config() — SYSTEM-DESIGN-V4.md
 * Phase 0 stop-loss item #4: refuse to start in production with dev-grade
 * secrets or an unset database URL. Runs via @PostConstruct so it fires
 * before the app finishes starting (and before it can bind a port), same
 * fail-fast intent as the Python version running at import time. */
@Component
public class ProductionConfigChecker {
    private static final String DEV_JWT_SECRET = "dev-only-insecure-secret-change-me";
    private final AppProperties props;

    public ProductionConfigChecker(AppProperties props) { this.props = props; }

    @PostConstruct
    public void check() {
        if (!"production".equals(props.getEnvironment())) return;
        List<String> problems = new ArrayList<>();
        if (DEV_JWT_SECRET.equals(props.getJwtSecret())) {
            problems.add("JWT_SECRET 未设置或仍为开发默认值");
        } else if (props.getJwtSecret().getBytes(StandardCharsets.UTF_8).length < 32) {
            problems.add("JWT_SECRET 长度不足 32 字节");
        }
        if (props.getAdminPassword() == null || props.getAdminPassword().isBlank() || "admin123".equals(props.getAdminPassword())) {
            problems.add("ADMIN_PASSWORD 未设置或仍为开发默认值");
        }
        if (System.getenv("DATABASE_URL") == null || System.getenv("DATABASE_URL").isBlank()) {
            problems.add("DATABASE_URL 未设置，未指向生产数据库");
        }
        if (!problems.isEmpty()) {
            StringBuilder detail = new StringBuilder();
            for (String p : problems) detail.append("\n  - ").append(p);
            throw new IllegalStateException("生产环境配置缺失，拒绝启动：" + detail);
        }
    }
}
