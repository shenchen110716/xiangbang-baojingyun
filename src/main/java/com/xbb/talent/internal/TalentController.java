package com.xbb.talent.internal;

import com.xbb.talent.api.TalentApi;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/talent")
class TalentController {

    private final TalentApi talentApi;

    TalentController(TalentApi talentApi) {
        this.talentApi = talentApi;
    }

    @GetMapping("/search")
    ResponseEntity<List<TalentApi.TalentView>> search(@RequestParam List<String> tags,
                                                        @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(talentApi.search(tags, limit));
    }

    @GetMapping("/{userId}")
    ResponseEntity<TalentApi.TalentView> get(@PathVariable long userId) {
        return talentApi.findTalent(userId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
