package com.xbb.baojing.web;

import com.xbb.baojing.common.AppProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

/** Ports backend/app.py's static-serving section: the Vue admin build
 * (web/dist/) is mounted ONLY at /assets — never the whole project root —
 * matching the "explicit allowlist, not StaticFiles(directory=ROOT)"
 * security posture from SYSTEM-DESIGN-V4.md Phase 0. The SPA fallback
 * itself (unknown-route -> index.html) lives in StaticFrontendController
 * as an explicit route allowlist, not a wildcard, for the same reason. */
@Configuration
public class StaticFrontendConfig implements WebMvcConfigurer {
    private final AppProperties appProperties;

    public StaticFrontendConfig(AppProperties appProperties) { this.appProperties = appProperties; }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String assetsDir = Paths.get(appProperties.getWebDistDir(), "assets").toUri().toString();
        registry.addResourceHandler("/assets/**").addResourceLocations(assetsDir);
    }
}
