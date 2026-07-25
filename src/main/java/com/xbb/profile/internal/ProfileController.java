package com.xbb.profile.internal;

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

    @PostMapping("/tags")
    ResponseEntity<Void> submitTags(@AuthenticationPrincipal AuthenticatedUser caller, @RequestBody TagsRequest req) {
        profileApi.submitTags(caller.userId(), req.tags());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/tags")
    ResponseEntity<List<ProfileApi.ProfileTagView>> getTags(@AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.ok(profileApi.getProfile(caller.userId()));
    }

    // 400/409 等错误映射统一收在 com.xbb.web.GlobalExceptionHandler
}
