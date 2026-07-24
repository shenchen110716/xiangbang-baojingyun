package com.xbb.job.internal;

import com.xbb.job.api.JobApi;
import com.xbb.security.AuthenticatedUser;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/job")
class JobController {

    private final JobApi jobApi;

    JobController(JobApi jobApi) {
        this.jobApi = jobApi;
    }

    record PostJobRequest(@Positive long orgId, @NotBlank String title,
                           @NotBlank String description, @Positive long wageCents) { }

    @PostMapping
    ResponseEntity<Map<String, Long>> postJob(@AuthenticationPrincipal AuthenticatedUser caller,
                                               @RequestBody PostJobRequest req) {
        long id = jobApi.postJob(req.orgId(), req.title(), req.description(), req.wageCents(), caller.userId());
        return ResponseEntity.ok(Map.of("id", id));
    }

    @GetMapping("/{id}")
    ResponseEntity<JobApi.JobView> get(@PathVariable long id) {
        return jobApi.findJob(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/apply")
    ResponseEntity<Map<String, Long>> apply(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable long id) {
        long applicationId = jobApi.apply(id, caller.userId());
        return ResponseEntity.ok(Map.of("id", applicationId));
    }

    // 400/409 等错误映射统一收在 com.xbb.web.GlobalExceptionHandler
}
