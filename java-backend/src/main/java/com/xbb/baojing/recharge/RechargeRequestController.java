package com.xbb.baojing.recharge;

import com.xbb.baojing.common.*;
import com.xbb.baojing.enterprise.Enterprise;
import com.xbb.baojing.enterprise.EnterpriseMapper;
import com.xbb.baojing.finance.LedgerService;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/** Ports backend/routers/recharge_requests.py — enterprise submits a bank-
 * transfer recharge request (with a receipt), admin reviews confirm/reject.
 * The target collection account is resolved and frozen at submission time
 * (never re-resolved at confirm) so a later admin re-link cannot misdirect
 * an already-submitted request. */
@RestController
@RequestMapping("/api")
public class RechargeRequestController {
    private final RechargeRequestMapper requestMapper;
    private final EnterpriseMapper enterpriseMapper;
    private final EnterprisePremiumAccountMapper premiumAccountMapper;
    private final RechargeService rechargeService;
    private final LedgerService ledgerService;
    private final AuditService auditService;
    private final FileTokenService fileTokenService;
    private final String uploadsDir;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> ALLOWED_SUFFIXES = Set.of(".pdf", ".jpg", ".jpeg", ".png");

    public RechargeRequestController(RechargeRequestMapper requestMapper, EnterpriseMapper enterpriseMapper,
                                      EnterprisePremiumAccountMapper premiumAccountMapper, RechargeService rechargeService,
                                      LedgerService ledgerService, AuditService auditService,
                                      FileTokenService fileTokenService, AppProperties props) {
        this.requestMapper = requestMapper;
        this.enterpriseMapper = enterpriseMapper;
        this.premiumAccountMapper = premiumAccountMapper;
        this.rechargeService = rechargeService;
        this.ledgerService = ledgerService;
        this.auditService = auditService;
        this.fileTokenService = fileTokenService;
        this.uploadsDir = props.getUploadsDir();
    }

    private String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private RechargeRequest withJoins(RechargeRequest item) {
        Enterprise enterprise = enterpriseMapper.findById(item.getEnterpriseId());
        item.setEnterpriseName(enterprise != null ? enterprise.getName() : "");
        if (item.getReceiptFileUrl() != null && !item.getReceiptFileUrl().isBlank()) {
            FileTokenService.Token token = fileTokenService.makeToken("recharge-receipt:" + item.getId());
            item.setReceiptDownloadUrl("/api/recharge-requests/" + item.getId() + "/receipt?token=" + token.token() + "&expires=" + token.expires());
        }
        return item;
    }

    @PostMapping("/recharge-requests")
    public RechargeRequest create(@RequestParam("enterprise_id") int enterpriseId, @RequestParam("account_type") String accountType,
                                   @RequestParam(name = "insurer", defaultValue = "") String insurer, @RequestParam double amount,
                                   @RequestParam MultipartFile file, User user) throws IOException {
        Rbac.requireRole(user, "无权发起充值申请", "admin", "enterprise");
        if ("enterprise".equals(user.getRole()) && !Integer.valueOf(enterpriseId).equals(user.getEnterpriseId())) throw ApiException.forbidden("无权为其他单位发起充值");
        if (enterpriseMapper.findById(enterpriseId) == null) throw ApiException.notFound("投保单位不存在");
        if (!Set.of("premium", "usage").contains(accountType)) throw ApiException.badRequest("账户类型不合法");
        if (amount <= 0) throw ApiException.badRequest("充值金额必须大于 0");

        Integer accountId = null;
        String resolvedInsurer = null;
        if ("premium".equals(accountType)) {
            if (insurer.isBlank()) throw ApiException.badRequest("请选择保司");
            InsurerAccount account = rechargeService.resolveAccountForInsurer(insurer.trim());
            if (account == null) throw ApiException.badRequest("该保司尚未配置收款账户，请联系平台");
            accountId = account.getId();
            resolvedInsurer = insurer.trim();
        }

        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String suffix = original.contains(".") ? original.substring(original.lastIndexOf('.')).toLowerCase() : "";
        if (!ALLOWED_SUFFIXES.contains(suffix)) throw ApiException.badRequest("仅支持 PDF 或图片格式");
        if (file.isEmpty()) throw ApiException.badRequest("回单文件为空，请重新选择");
        if (file.getSize() > 20L * 1024 * 1024) throw ApiException.badRequest("文件不能超过 20MB");

        Path folder = Paths.get(uploadsDir, "recharge-receipts", String.valueOf(enterpriseId));
        Files.createDirectories(folder);
        String stored = randomHex(8) + suffix;
        file.transferTo(folder.resolve(stored));
        String url = "/uploads/recharge-receipts/" + enterpriseId + "/" + stored;

        RechargeRequest item = new RechargeRequest();
        item.setEnterpriseId(enterpriseId);
        item.setAccountType(accountType);
        item.setInsurer(resolvedInsurer);
        item.setAccountId(accountId);
        item.setAmount(amount);
        item.setReceiptFileUrl(url);
        item.setStatus("pending");
        item.setCreatedBy(user.getId());
        item.setCreatedAt(LocalDateTime.now());
        requestMapper.insert(item);
        auditService.log(user, "create", "recharge_request", String.valueOf(item.getId()), accountType + ":" + amount);
        return withJoins(item);
    }

    @GetMapping("/recharge-requests")
    public List<RechargeRequest> list(@RequestParam(name = "status", defaultValue = "") String status, User user) {
        Integer enterpriseId;
        if ("enterprise".equals(user.getRole()) && user.getEnterpriseId() != null) {
            enterpriseId = user.getEnterpriseId();
        } else if ("admin".equals(user.getRole())) {
            enterpriseId = null;
        } else {
            throw ApiException.forbidden("无权查看充值记录");
        }
        return requestMapper.search(enterpriseId, status.isBlank() ? null : status).stream().map(this::withJoins).toList();
    }

    @PatchMapping("/recharge-requests/{id}/confirm")
    public RechargeRequest confirm(@PathVariable int id, User user) {
        Rbac.requireRole(user, "仅总后台可确认充值", "admin");
        RechargeRequest item = requestMapper.findById(id);
        if (item == null) throw ApiException.notFound("充值申请不存在");
        if (!"pending".equals(item.getStatus())) throw ApiException.badRequest("该申请已处理，不能重复确认");
        Enterprise enterprise = enterpriseMapper.findById(item.getEnterpriseId());
        if ("premium".equals(item.getAccountType())) {
            EnterprisePremiumAccount premiumAccount = rechargeService.getOrCreatePremiumAccount(item.getEnterpriseId(), item.getAccountId());
            premiumAccount.setBalance(premiumAccount.getBalance() + item.getAmount());
            premiumAccountMapper.update(premiumAccount);
            ledgerService.postEntry(enterprise, "premium", "credit", item.getAmount(), "recharge_request", String.valueOf(item.getId()), user, "", item.getAccountId());
        } else {
            enterprise.setUsageBalance(enterprise.getUsageBalance() + item.getAmount());
            enterpriseMapper.update(enterprise);
            ledgerService.postEntry(enterprise, "usage", "credit", item.getAmount(), "recharge_request", String.valueOf(item.getId()), user);
        }
        item.setStatus("confirmed");
        item.setConfirmedBy(user.getId());
        item.setConfirmedAt(LocalDateTime.now());
        requestMapper.update(item);
        auditService.log(user, "confirm", "recharge_request", String.valueOf(item.getId()));
        return withJoins(item);
    }

    @PatchMapping("/recharge-requests/{id}/reject")
    public RechargeRequest reject(@PathVariable int id, @RequestParam String reason, User user) {
        Rbac.requireRole(user, "仅总后台可驳回充值", "admin");
        RechargeRequest item = requestMapper.findById(id);
        if (item == null) throw ApiException.notFound("充值申请不存在");
        if (!"pending".equals(item.getStatus())) throw ApiException.badRequest("该申请已处理，不能重复驳回");
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("驳回时必须填写原因");
        item.setStatus("rejected");
        item.setRejectReason(reason.trim());
        item.setConfirmedBy(user.getId());
        item.setConfirmedAt(LocalDateTime.now());
        requestMapper.update(item);
        auditService.log(user, "reject", "recharge_request", String.valueOf(item.getId()), reason);
        return withJoins(item);
    }

    @GetMapping("/recharge-requests/{id}/receipt")
    public ResponseEntity<Resource> receipt(@PathVariable int id, @RequestParam String token, @RequestParam long expires) throws IOException {
        if (!fileTokenService.verify("recharge-receipt:" + id, expires, token)) throw ApiException.forbidden("下载链接无效或已过期");
        RechargeRequest item = requestMapper.findById(id);
        if (item == null || item.getReceiptFileUrl() == null || item.getReceiptFileUrl().isBlank()) throw ApiException.notFound("回单不存在");
        Path path = Paths.get(".", item.getReceiptFileUrl());
        if (!Files.isRegularFile(path)) throw ApiException.notFound("文件不存在");
        Resource resource = new UrlResource(path.toUri());
        return ResponseEntity.ok().body(resource);
    }
}
