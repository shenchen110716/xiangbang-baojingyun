package com.xbb.engagement.internal;

import com.xbb.engagement.api.EngagementApi;
import com.xbb.security.AuthenticatedUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/engagement")
class EngagementController {

    private final EngagementApi engagementApi;

    EngagementController(EngagementApi engagementApi) {
        this.engagementApi = engagementApi;
    }

    @PostMapping("/{jobId}/apply")
    ResponseEntity<Map<String, Long>> apply(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable long jobId) {
        long applicationId = engagementApi.apply(jobId, caller.userId());
        return ResponseEntity.ok(Map.of("id", applicationId));
    }

    /** 我的报名。排在 /{id} 之前。 */
    @GetMapping("/mine")
    ResponseEntity<List<EngagementApi.ApplicationView>> mine(@AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.ok(engagementApi.listMyApplications(caller.userId()));
    }

    /** 某岗位的应聘者。归属校验在服务层(只有法人代表能看)。 */
    @GetMapping("/job/{jobId}/applicants")
    ResponseEntity<List<EngagementApi.ApplicationView>> applicants(
            @PathVariable long jobId, @AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.ok(engagementApi.listJobApplicants(jobId, caller.userId()));
    }

    @GetMapping("/{id}")
    ResponseEntity<EngagementApi.ApplicationView> get(@PathVariable long id,
                                                       @AuthenticationPrincipal AuthenticatedUser caller) {
        return engagementApi.findApplication(id, caller.userId()).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}/accept")
    ResponseEntity<Void> accept(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable long id) {
        engagementApi.acceptApplication(id, caller.userId());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/reject")
    ResponseEntity<Void> reject(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable long id) {
        engagementApi.rejectApplication(id, caller.userId());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/complete")
    ResponseEntity<Void> complete(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable long id) {
        engagementApi.completeApplication(id, caller.userId());
        return ResponseEntity.noContent().build();
    }

    // 400/409 等错误映射统一收在 com.xbb.web.GlobalExceptionHandler
}
