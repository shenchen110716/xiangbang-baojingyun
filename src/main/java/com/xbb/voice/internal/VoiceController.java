package com.xbb.voice.internal;

import com.xbb.security.AuthenticatedUser;
import com.xbb.voice.api.VoiceApi;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/voice")
class VoiceController {

    private final VoiceApi voiceApi;

    VoiceController(VoiceApi voiceApi) {
        this.voiceApi = voiceApi;
    }

    record DraftRequest(long orgId, String title, int headcount, long wageCents, String extra) { }

    record ConfirmRequest(String utterance) { }

    @PostMapping("/job/draft")
    ResponseEntity<VoiceApi.Draft> draft(@AuthenticationPrincipal AuthenticatedUser caller,
                                          @RequestBody DraftRequest req) {
        return ResponseEntity.ok(voiceApi.draftJob(caller.userId(), req.orgId(), req.title(),
                req.headcount(), req.wageCents(), req.extra()));
    }

    @PostMapping("/job/{sessionId}/confirm")
    ResponseEntity<VoiceApi.ConfirmResult> confirm(@AuthenticationPrincipal AuthenticatedUser caller,
                                                     @PathVariable long sessionId,
                                                     @RequestBody ConfirmRequest req) {
        return ResponseEntity.ok(voiceApi.confirm(sessionId, caller.userId(), req.utterance()));
    }

    @PostMapping("/job/{sessionId}/recall")
    ResponseEntity<VoiceApi.ConfirmResult> recall(@AuthenticationPrincipal AuthenticatedUser caller,
                                                    @PathVariable long sessionId) {
        return ResponseEntity.ok(voiceApi.recall(sessionId, caller.userId()));
    }

    // 400/409 等错误映射统一收在 com.xbb.web.GlobalExceptionHandler
}
