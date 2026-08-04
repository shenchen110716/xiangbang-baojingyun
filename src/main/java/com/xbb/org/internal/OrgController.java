package com.xbb.org.internal;

import jakarta.validation.Valid;
import com.xbb.org.api.OrgApi;
import com.xbb.security.AuthenticatedUser;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/org")
class OrgController {

    private final OrgApi orgApi;

    OrgController(OrgApi orgApi) {
        this.orgApi = orgApi;
    }

    record SubmitRequest(@NotNull com.xbb.org.api.OrgType type, @NotBlank String name,
                          @NotBlank String creditCode) { }

    @PostMapping
    ResponseEntity<Map<String, Long>> submit(@AuthenticationPrincipal AuthenticatedUser caller,
                                              @RequestBody @Valid SubmitRequest req) {
        // 法人代表 = 调用者本人(来自 JWT),不信任请求体——否则任何人都能代别人提交入驻
        long id = orgApi.submit(req.type(), req.name(), req.creditCode(), caller.userId());
        return ResponseEntity.ok(Map.of("id", id));
    }

    /** 我作为法人代表的组织。注意要排在 /{id} 之前,否则 "mine" 会被当成路径变量。 */
    @GetMapping("/mine")
    ResponseEntity<List<OrgApi.OrgView>> mine(@AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.ok(orgApi.listByLegalRep(caller.userId()));
    }

    /** 待审核队列。角色校验在服务层,控制器不重复判断——两处判断迟早会分叉。 */
    @GetMapping("/pending")
    ResponseEntity<List<OrgApi.OrgView>> pending(@AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.ok(orgApi.listPending(caller.userId()));
    }

    @GetMapping("/{id}")
    ResponseEntity<OrgApi.OrgView> get(@PathVariable long id) {
        return orgApi.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}/approve")
    ResponseEntity<Void> approve(@PathVariable long id,
                                  @AuthenticationPrincipal AuthenticatedUser caller) {
        orgApi.approve(id, caller.userId());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/reject")
    ResponseEntity<Void> reject(@PathVariable long id,
                                 @AuthenticationPrincipal AuthenticatedUser caller) {
        orgApi.reject(id, caller.userId());
        return ResponseEntity.noContent().build();
    }

    // 400/409/乐观锁冲突等错误映射统一收在 com.xbb.web.GlobalExceptionHandler
}
