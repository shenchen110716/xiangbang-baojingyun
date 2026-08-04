package com.xbb.ops.internal;

import com.xbb.identity.api.IdentityApi;
import com.xbb.identity.api.Role;
import com.xbb.ops.api.OpsApi;
import com.xbb.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * 运营域的管理入口:平台参数、受控词表、协议模板。
 *
 * <p>这三样此前**都没有任何 HTTP 端点**。{@code OpsApi} 有完整的增删改查,
 * 各域也在用,但平台侧没有办法从界面上改一个字 —— 能力在,网上够不着。
 * 这是同一形态的第三例(前两例是角色管理与监管账户入账),所以顺手一起补上。
 *
 * <p>写操作一律要 {@code PLATFORM_OPS}。词表的读取不设限:各域本来就在内部读它,
 * 而"平台承认哪些技能标签"不是机密。
 */
@RestController
@RequestMapping("/api/ops")
class OpsAdminController {

    private final OpsApi opsApi;
    private final IdentityApi identityApi;

    OpsAdminController(OpsApi opsApi, IdentityApi identityApi) {
        this.opsApi = opsApi;
        this.identityApi = identityApi;
    }

    private void requireOps(AuthenticatedUser caller) {
        if (caller == null || !identityApi.hasRole(caller.userId(), Role.PLATFORM_OPS)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要平台运维角色");
        }
    }

    // ── 平台参数 ──

    record UpdateSettingRequest(@NotBlank String value, @NotBlank String reason) { }

    /** 参数列表。读也要 OPS:佣金比例属于经营信息,不该对所有登录用户开放。 */
    @GetMapping("/settings")
    ResponseEntity<List<OpsApi.SettingView>> settings(@AuthenticationPrincipal AuthenticatedUser caller) {
        requireOps(caller);
        return ResponseEntity.ok(opsApi.allSettings());
    }

    @PutMapping("/settings/{key}")
    ResponseEntity<Void> updateSetting(@PathVariable String key,
                                       @RequestBody @Valid UpdateSettingRequest req,
                                       @AuthenticationPrincipal AuthenticatedUser caller) {
        // 角色校验在服务层也有一遍。这里不省,是因为控制器要给出 403 而不是 500;
        // 服务层那遍则保证"绕开控制器直接调 API"同样挡得住。
        requireOps(caller);
        opsApi.updateSetting(key, req.value(), req.reason(), caller.userId());
        return ResponseEntity.noContent().build();
    }

    /** 参数改动记录。key 省略时给最近 50 条。 */
    @GetMapping("/settings/changes")
    ResponseEntity<List<OpsApi.SettingChangeView>> settingChanges(
            @RequestParam(required = false) String key,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        requireOps(caller);
        return ResponseEntity.ok(opsApi.settingChanges(key, caller.userId()));
    }

    // ── 受控词表 ──

    record AddItemRequest(@NotBlank String dictType, @NotBlank String key, @NotBlank String value,
                          int sortOrder, Map<String, String> attributes) { }
    record UpdateValueRequest(@NotBlank String value) { }

    @GetMapping("/dict/{dictType}")
    ResponseEntity<List<OpsApi.DictItemView>> dict(@PathVariable String dictType) {
        return ResponseEntity.ok(opsApi.itemsOf(dictType));
    }

    @PostMapping("/dict")
    ResponseEntity<Map<String, Long>> addItem(@RequestBody @Valid AddItemRequest req,
                                              @AuthenticationPrincipal AuthenticatedUser caller) {
        requireOps(caller);
        long id = opsApi.addItem(req.dictType(), req.key(), req.value(), req.sortOrder(), req.attributes());
        return ResponseEntity.ok(Map.of("id", id));
    }

    @PutMapping("/dict/{id}/value")
    ResponseEntity<Void> updateItemValue(@PathVariable long id, @RequestBody @Valid UpdateValueRequest req,
                                         @AuthenticationPrincipal AuthenticatedUser caller) {
        requireOps(caller);
        opsApi.updateValue(id, req.value());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/dict/{id}/attributes")
    ResponseEntity<Void> updateItemAttributes(@PathVariable long id, @RequestBody Map<String, String> attrs,
                                              @AuthenticationPrincipal AuthenticatedUser caller) {
        requireOps(caller);
        opsApi.updateAttributes(id, attrs);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/dict/{id}/enabled")
    ResponseEntity<Void> toggleItem(@PathVariable long id, @RequestParam boolean enabled,
                                    @AuthenticationPrincipal AuthenticatedUser caller) {
        requireOps(caller);
        if (enabled) { opsApi.enableItem(id); } else { opsApi.disableItem(id); }
        return ResponseEntity.noContent().build();
    }

    // ── 协议模板 ──

    record PublishTemplateRequest(@NotBlank String templateKey, @NotBlank String body) { }

    @GetMapping("/templates/{templateKey}")
    ResponseEntity<OpsApi.AgreementTemplateView> activeTemplate(@PathVariable String templateKey,
                                                                @AuthenticationPrincipal AuthenticatedUser caller) {
        requireOps(caller);
        return opsApi.activeTemplate(templateKey)
                .map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 发布模板新版本。**只增不改**:已生效版本的正文永不修改,
     * 否则签过的协议就追溯不到当时的文本了。
     */
    @PostMapping("/templates")
    ResponseEntity<Map<String, Integer>> publishTemplate(@RequestBody @Valid PublishTemplateRequest req,
                                                         @AuthenticationPrincipal AuthenticatedUser caller) {
        requireOps(caller);
        return ResponseEntity.ok(Map.of("version", opsApi.publishTemplate(req.templateKey(), req.body())));
    }
}
