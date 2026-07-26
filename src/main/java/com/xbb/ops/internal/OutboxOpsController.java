package com.xbb.ops.internal;

import com.xbb.AbstractOutboxRelay;
import com.xbb.OutboxAdmin;
import com.xbb.identity.api.IdentityApi;
import com.xbb.identity.api.Role;
import com.xbb.security.AuthenticatedUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * outbox 的运维出口(§4.2 运营域)。
 *
 * <p><b>鉴权每次都问身份域,不看 token 里的声明。</b>重放一条资金事件是高权限操作:
 * 角色一旦被收回必须立刻失效,而写进 JWT 的声明要等 token 过期才失效。
 * 代价是每次请求多一次身份域查询,在这种量级的接口上完全值得。
 */
@RestController
@RequestMapping("/api/ops/outbox")
class OutboxOpsController {

    private final OutboxAdmin admin;
    private final IdentityApi identityApi;

    OutboxOpsController(OutboxAdmin admin, IdentityApi identityApi) {
        this.admin = admin;
        this.identityApi = identityApi;
    }

    private void requireOps(AuthenticatedUser caller) {
        if (caller == null || !identityApi.hasRole(caller.userId(), Role.PLATFORM_OPS)) {
            throw new AccessDeniedException("需要平台运维权限");
        }
    }

    @GetMapping("/stuck")
    ResponseEntity<List<AbstractOutboxRelay.StuckEvent>> stuck(@AuthenticationPrincipal AuthenticatedUser caller) {
        requireOps(caller);
        return ResponseEntity.ok(admin.stuckEvents());
    }

    @PostMapping("/{domain}/{eventId}/replay")
    ResponseEntity<Map<String, Object>> replay(@AuthenticationPrincipal AuthenticatedUser caller,
                                                @PathVariable String domain,
                                                @PathVariable String eventId) {
        requireOps(caller);
        boolean found = admin.replay(domain, eventId);
        return found
                ? ResponseEntity.ok(Map.of("replayed", true))
                : ResponseEntity.status(404).body(Map.of("error", "没有这条事件: " + eventId));
    }
}
