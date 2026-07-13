package com.xbb.baojing.finance;

import com.xbb.baojing.common.*;
import com.xbb.baojing.enterprise.Enterprise;
import com.xbb.baojing.enterprise.EnterpriseMapper;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class PaymentController {
    private final PaymentRecordMapper paymentMapper;
    private final EnterpriseMapper enterpriseMapper;
    private final Providers providers;
    private final LedgerService ledgerService;
    private static final SecureRandom RANDOM = new SecureRandom();

    public PaymentController(PaymentRecordMapper paymentMapper, EnterpriseMapper enterpriseMapper, Providers providers, LedgerService ledgerService) {
        this.paymentMapper = paymentMapper;
        this.enterpriseMapper = enterpriseMapper;
        this.providers = providers;
        this.ledgerService = ledgerService;
    }

    public record PaymentIn(int enterpriseId, String account, double amount) {}
    public record PaymentCallbackIn(String orderNo, String status, String providerTradeNo) {}

    @PostMapping("/payments")
    public Map<String, Object> create(@RequestBody PaymentIn data, User user) {
        Rbac.requireRole(user, "无权创建充值订单", "admin", "enterprise");
        Rbac.assertEnterpriseScope(user, data.enterpriseId(), "无权为该单位充值");
        if (enterpriseMapper.findById(data.enterpriseId()) == null) throw ApiException.notFound("投保单位不存在");
        String orderNo = "PAY-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + "-" + randomHex(3);
        var result = providers.paymentProvider().createPayment(data.amount(), orderNo);
        PaymentRecord row = new PaymentRecord();
        row.setOrderNo(orderNo);
        row.setEnterpriseId(data.enterpriseId());
        row.setAccount(data.account() == null ? "premium" : data.account());
        row.setAmount(data.amount());
        row.setStatus("pending");
        row.setProvider(result.provider());
        row.setCreatedAt(LocalDateTime.now());
        paymentMapper.insert(row);
        return Map.of("order_no", orderNo, "status", row.getStatus(), "pay_url", result.data().getOrDefault("pay_url", ""), "request_id", result.requestId());
    }

    private String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }

    @PostMapping("/payments/callback")
    public Map<String, Object> callback(@RequestBody PaymentCallbackIn data) {
        PaymentRecord row = paymentMapper.findByOrderNo(data.orderNo());
        if (row == null) throw ApiException.notFound("支付订单不存在");
        String previous = row.getStatus();
        if ("paid".equals(previous)) return Map.of("ok", true, "order_no", row.getOrderNo(), "status", row.getStatus(), "idempotent", true);
        row.setStatus(data.status());
        if ("paid".equals(data.status())) {
            Enterprise enterprise = enterpriseMapper.findById(row.getEnterpriseId());
            if ("premium".equals(row.getAccount())) enterprise.setPremiumBalance(enterprise.getPremiumBalance() + row.getAmount());
            else enterprise.setUsageBalance(enterprise.getUsageBalance() + row.getAmount());
            ledgerService.postEntry(enterprise, row.getAccount(), "credit", row.getAmount(), "payment", row.getOrderNo(), null, row.getOrderNo());
            enterpriseMapper.update(enterprise);
        }
        paymentMapper.update(row);
        return Map.of("ok", true, "order_no", row.getOrderNo(), "status", row.getStatus(), "idempotent", false);
    }

    @GetMapping("/payments/reconcile")
    public Map<String, Object> reconcile(User user) {
        Rbac.requireRole(user, "仅总后台可对账", "admin");
        return Map.of("pending", paymentMapper.countByStatus("pending"), "paid", paymentMapper.countByStatus("paid"), "failed", paymentMapper.countByStatus("failed"));
    }
}
