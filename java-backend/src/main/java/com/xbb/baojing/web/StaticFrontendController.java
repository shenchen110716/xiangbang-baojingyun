package com.xbb.baojing.web;

import com.xbb.baojing.common.AppProperties;
import com.xbb.baojing.common.ApiException;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

/** Ports backend/app.py's serve_frontend(): an explicit allowlist of the 19
 * known Vue-router client routes (mirrors web/src/router/routes.ts) falls
 * back to index.html for SPA history-mode navigation; a small allowlist of
 * root-level static files (favicon.svg, icons.svg) is served directly;
 * everything else 404s. The path pattern below excludes /api/**,
 * /assets/**, /uploads/** so this can never shadow those, regardless of
 * Spring's handler-mapping resolution order. */
@RestController
public class StaticFrontendController {
    private final Path webDist;

    private static final Set<String> WEB_ROOT_FILES = Set.of("favicon.svg", "icons.svg", "xbbzp.html");
    private static final Set<String> FRONTEND_ROUTES = Set.of(
            "", "home", "screen", "team", "dispatch", "workers", "work-relations",
            "agents", "insurance", "policy", "claims", "insurers", "exports",
            "report", "billing", "promotion", "operators", "message", "settings", "login"
    );

    public StaticFrontendController(AppProperties props) {
        this.webDist = Paths.get(props.getWebDistDir());
    }

    @GetMapping("/{path:^(?!api|assets|uploads).*}")
    public ResponseEntity<Resource> serve(@PathVariable(required = false) String path) {
        String p = path == null ? "" : path;
        if (WEB_ROOT_FILES.contains(p)) {
            Path file = webDist.resolve(p);
            if (!Files.isRegularFile(file)) throw ApiException.notFound("not found");
            return ResponseEntity.ok().body(new FileSystemResource(file));
        }
        if (FRONTEND_ROUTES.contains(p) || p.startsWith("certificate/")) {
            Path index = webDist.resolve("index.html");
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(new FileSystemResource(index));
        }
        throw ApiException.notFound("not found");
    }

    @GetMapping("/")
    public ResponseEntity<Resource> root() {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(new FileSystemResource(webDist.resolve("index.html")));
    }
}
