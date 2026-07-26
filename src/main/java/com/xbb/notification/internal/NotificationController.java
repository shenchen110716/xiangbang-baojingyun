package com.xbb.notification.internal;

import com.xbb.notification.api.NotificationApi;
import com.xbb.security.AuthenticatedUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notification")
class NotificationController {

    private final NotificationApi notificationApi;

    NotificationController(NotificationApi notificationApi) {
        this.notificationApi = notificationApi;
    }

    /** 收件人一律来自 JWT,不信任请求参数(IDOR 修复的既有约定)。 */
    @GetMapping
    ResponseEntity<List<NotificationApi.NotificationView>> inbox(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(notificationApi.inbox(caller.userId(), limit));
    }

    @GetMapping("/unread-count")
    ResponseEntity<Map<String, Long>> unreadCount(@AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.ok(Map.of("count", notificationApi.unreadCount(caller.userId())));
    }

    @PutMapping("/{id}/read")
    ResponseEntity<Void> markRead(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable long id) {
        notificationApi.markRead(id, caller.userId());
        return ResponseEntity.noContent().build();
    }
}
