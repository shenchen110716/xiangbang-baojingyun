package com.xbb.review.internal;

import com.xbb.review.api.ReviewApi;
import com.xbb.security.AuthenticatedUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/review")
class ReviewController {

    private final ReviewApi reviewApi;

    ReviewController(ReviewApi reviewApi) {
        this.reviewApi = reviewApi;
    }

    record SubmitRequest(List<String> tags, String comment) { }

    @PostMapping("/{applicationId}")
    ResponseEntity<Void> submit(@AuthenticationPrincipal AuthenticatedUser caller,
                                 @PathVariable long applicationId,
                                 @RequestBody SubmitRequest req) {
        reviewApi.submitReview(applicationId, caller.userId(), req.tags(), req.comment());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{applicationId}")
    ResponseEntity<List<ReviewApi.ReviewView>> visible(@PathVariable long applicationId) {
        return ResponseEntity.ok(reviewApi.findVisibleReviews(applicationId));
    }

    @GetMapping("/credit")
    ResponseEntity<ReviewApi.CreditView> myCredit(@AuthenticationPrincipal AuthenticatedUser caller) {
        return reviewApi.findCreditScore(caller.userId()).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // 400/409 等错误映射统一收在 com.xbb.web.GlobalExceptionHandler
}
