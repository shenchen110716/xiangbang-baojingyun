package com.xbb.matching.internal;

import com.xbb.matching.api.MatchingApi;
import com.xbb.security.AuthenticatedUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/matching")
class MatchingController {

    private final MatchingApi matchingApi;

    MatchingController(MatchingApi matchingApi) {
        this.matchingApi = matchingApi;
    }

    /** 给当前登录工人推岗位,userId 一律来自 JWT,不信任请求体(IDOR 修复的既有约定)。 */
    @GetMapping("/jobs")
    ResponseEntity<List<MatchingApi.MatchView>> jobsForMe(@AuthenticationPrincipal AuthenticatedUser caller,
                                                           @RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok(matchingApi.recommendJobsForWorker(caller.userId(), limit));
    }

    @GetMapping("/workers/{jobId}")
    ResponseEntity<List<MatchingApi.MatchView>> workersForJob(@PathVariable long jobId,
                                                               @RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok(matchingApi.recommendWorkersForJob(jobId, limit));
    }

    // 400/409 等错误映射统一收在 com.xbb.web.GlobalExceptionHandler
}
