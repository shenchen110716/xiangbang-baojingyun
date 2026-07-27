package com.xbb.profile.internal;

import jakarta.validation.Valid;
import com.xbb.profile.api.ProfileApi;
import com.xbb.security.AuthenticatedUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/profile")
class ProfileController {

    private final ProfileApi profileApi;

    ProfileController(ProfileApi profileApi) {
        this.profileApi = profileApi;
    }

    record TagsRequest(List<String> tags) { }

    record JobProfileRequest(List<String> mustTags, List<String> niceTags, double lat, double lon) { }

    record WorkerPreferenceRequest(long expectedWageCents, double lat, double lon) { }

    @PostMapping("/tags")
    ResponseEntity<Void> submitTags(@AuthenticationPrincipal AuthenticatedUser caller, @RequestBody @Valid TagsRequest req) {
        profileApi.submitTags(caller.userId(), req.tags());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/tags")
    ResponseEntity<List<ProfileApi.ProfileTagView>> getTags(@AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.ok(profileApi.getProfile(caller.userId()));
    }

    @PutMapping("/jobs/{jobId}")
    ResponseEntity<Void> setJobProfile(@PathVariable long jobId, @RequestBody @Valid JobProfileRequest req,
                                          @AuthenticationPrincipal AuthenticatedUser caller) {
        profileApi.setJobProfile(jobId, req.mustTags(), req.niceTags(), req.lat(), req.lon(), caller.userId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/jobs/{jobId}")
    ResponseEntity<ProfileApi.JobProfileView> getJobProfile(@PathVariable long jobId) {
        return profileApi.findJobProfile(jobId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/preference")
    ResponseEntity<Void> setPreference(@AuthenticationPrincipal AuthenticatedUser caller,
                                        @RequestBody @Valid WorkerPreferenceRequest req) {
        profileApi.setWorkerPreference(caller.userId(), req.expectedWageCents(), req.lat(), req.lon());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/preference")
    ResponseEntity<ProfileApi.WorkerPreferenceView> getPreference(@AuthenticationPrincipal AuthenticatedUser caller) {
        return profileApi.findWorkerPreference(caller.userId()).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // 400/409 等错误映射统一收在 com.xbb.web.GlobalExceptionHandler
}
