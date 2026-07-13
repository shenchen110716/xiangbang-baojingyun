package com.xbb.baojing.dashboard;

import com.xbb.baojing.common.*;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class MiscController {
    private final AuditLogMapper auditLogMapper;
    private final AuditService auditService;
    private final Providers providers;
    private final AppProperties appProperties;

    public MiscController(AuditLogMapper auditLogMapper, AuditService auditService, Providers providers, AppProperties appProperties) {
        this.auditLogMapper = auditLogMapper;
        this.auditService = auditService;
        this.providers = providers;
        this.appProperties = appProperties;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("ok", true, "service", "xiangbangbaojingyun", "time", LocalDateTime.now().toString());
    }

    @GetMapping("/providers/status")
    public Map<String, Object> providerStatus(User user) {
        return Map.of(
                "mode", appProperties.getIntegrationMode(),
                "insurer_api", System.getenv("INSURER_API_BASE_URL") != null,
                "sms", System.getenv("SMS_PROVIDER_URL") != null,
                "email", System.getenv("SMTP_HOST") != null,
                "payment", System.getenv("PAYMENT_PROVIDER_URL") != null);
    }

    public record NotificationIn(String kind, String recipient, String subject, String content, String template) {}

    @PostMapping("/notifications/send")
    public Map<String, Object> sendNotification(@RequestBody NotificationIn data, User user) {
        var result = "sms".equals(data.kind())
                ? providers.smsProvider().sendSms(data.recipient(), data.template(), Map.of("content", data.content()))
                : providers.emailProvider().sendEmail(data.recipient(), data.subject(), data.content(), null);
        auditService.log(user, "send", data.kind(), data.recipient(), result.message());
        return Map.of("ok", result.ok(), "provider", result.provider(), "request_id", result.requestId(), "message", result.message());
    }

    @GetMapping("/audit-logs")
    public List<AuditLog> auditLogs(@RequestParam(defaultValue = "100") int limit, User user) {
        if ("enterprise".equals(user.getRole())) return auditLogMapper.findRecentForEnterprise(user.getEnterpriseId(), limit);
        if (!"admin".equals(user.getRole())) throw ApiException.forbidden("无权查看审计日志");
        return auditLogMapper.findRecent(limit);
    }
}
