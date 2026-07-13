package com.xbb.baojing.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Ports backend/providers.py — the external-service adapter layer. Defaults
 * to a mock implementation so the whole system is exercisable without real
 * insurer/SMS/email/payment credentials; switches to a generic JSON-HTTP
 * adapter when INTEGRATION_MODE=real. */
@Component
public class Providers {
    private final AppProperties props;
    private static final ObjectMapper JSON = new ObjectMapper();

    public Providers(AppProperties props) { this.props = props; }

    public record ProviderResult(boolean ok, String provider, String requestId, Map<String, Object> data, String message) {}

    public interface Provider {
        ProviderResult submitEnrollment(Map<String, Object> payload);
        ProviderResult submitTermination(Map<String, Object> payload);
        ProviderResult sendSms(String phone, String template, Map<String, Object> params);
        ProviderResult sendEmail(String to, String subject, String body, List<Map<String, Object>> attachments);
        ProviderResult createPayment(double amount, String orderNo);
    }

    public static class MockProvider implements Provider {
        protected final String name;
        public MockProvider(String name) { this.name = name; }

        @Override
        public ProviderResult submitEnrollment(Map<String, Object> payload) {
            int count = payload.get("people") instanceof List<?> l ? l.size() : 0;
            return new ProviderResult(true, name, "MOCK-" + System.currentTimeMillis() / 1000, Map.of("accepted", count, "mode", "mock"), "模拟提交成功");
        }
        @Override
        public ProviderResult submitTermination(Map<String, Object> payload) {
            int count = payload.get("people") instanceof List<?> l ? l.size() : 0;
            return new ProviderResult(true, name, "MOCK-" + System.currentTimeMillis() / 1000, Map.of("accepted", count, "mode", "mock"), "模拟停保成功");
        }
        @Override
        public ProviderResult sendSms(String phone, String template, Map<String, Object> params) {
            return new ProviderResult(true, name, "MOCK-SMS", Map.of("phone", phone, "template", template), "模拟短信已记录");
        }
        @Override
        public ProviderResult sendEmail(String to, String subject, String body, List<Map<String, Object>> attachments) {
            List<String> names = attachments == null ? List.of() : attachments.stream().map(a -> String.valueOf(a.getOrDefault("filename", ""))).toList();
            return new ProviderResult(true, name, "MOCK-EMAIL", Map.of("to", to, "subject", subject, "attachments", names), "模拟邮件及名单附件已记录");
        }
        @Override
        public ProviderResult createPayment(double amount, String orderNo) {
            return new ProviderResult(true, name, orderNo, Map.of("amount", amount, "pay_url", "/mock-pay/" + orderNo), "模拟支付单已创建");
        }
    }

    /** Generic JSON-over-HTTP adapter for INTEGRATION_MODE=real — POSTs the
     * payload to the configured URL and expects {"request_id": ...} back.
     * Different insurers/suppliers get their own adapter by env var URL. */
    public static class HttpProvider extends MockProvider {
        private final String url;
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

        public HttpProvider(String name, String url) { super(name); this.url = url; }

        @SuppressWarnings("unchecked")
        private ProviderResult post(Map<String, Object> payload, String requestId) {
            try {
                String body = JSON.writeValueAsString(payload);
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(15))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                Map<String, Object> data = response.body() == null || response.body().isBlank() ? Map.of() : JSON.readValue(response.body(), Map.class);
                String actualRequestId = data.getOrDefault("request_id", requestId).toString();
                return new ProviderResult(true, name, actualRequestId, data, "已发送至真实接口");
            } catch (Exception e) {
                return new ProviderResult(false, name, requestId, Map.of(), "接口发送失败：" + e.getMessage());
            }
        }

        @Override public ProviderResult submitEnrollment(Map<String, Object> payload) { return post(payload, "ENR-" + System.currentTimeMillis() / 1000); }
        @Override public ProviderResult submitTermination(Map<String, Object> payload) { return post(payload, "TER-" + System.currentTimeMillis() / 1000); }
        @Override public ProviderResult sendSms(String phone, String template, Map<String, Object> params) { return post(Map.of("phone", phone, "template", template, "params", params), "SMS-" + System.currentTimeMillis() / 1000); }
        @Override public ProviderResult sendEmail(String to, String subject, String body, List<Map<String, Object>> attachments) { return post(Map.of("to", to, "subject", subject, "body", body, "attachments", attachments == null ? List.of() : attachments), "MAIL-" + System.currentTimeMillis() / 1000); }
        @Override public ProviderResult createPayment(double amount, String orderNo) { return post(Map.of("amount", amount, "order_no", orderNo), orderNo); }
    }

    private String mode() { return props.getIntegrationMode(); }

    public Provider insurerProvider(String name) { return "mock".equals(mode()) ? new MockProvider(name) : new HttpProvider(name, System.getenv().getOrDefault("INSURER_API_BASE_URL", "")); }
    public Provider smsProvider() { return "mock".equals(mode()) ? new MockProvider("sms") : new HttpProvider("sms", System.getenv().getOrDefault("SMS_PROVIDER_URL", "")); }
    public Provider emailProvider() { return "mock".equals(mode()) ? new MockProvider("smtp") : new HttpProvider("smtp", System.getenv().getOrDefault("EMAIL_PROVIDER_URL", "")); }
    public Provider paymentProvider() { return "mock".equals(mode()) ? new MockProvider("payment") : new HttpProvider("payment", System.getenv().getOrDefault("PAYMENT_PROVIDER_URL", "")); }
}
