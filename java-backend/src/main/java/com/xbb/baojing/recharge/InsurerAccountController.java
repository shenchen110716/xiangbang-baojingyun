package com.xbb.baojing.recharge;

import com.xbb.baojing.common.*;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Ports backend/routers/insurer_accounts.py — admin-only management of
 * collection accounts and the insurer-name-to-account links on them. */
@RestController
@RequestMapping("/api")
public class InsurerAccountController {
    private final InsurerAccountMapper accountMapper;
    private final InsurerAccountLinkMapper linkMapper;
    private final RechargeService rechargeService;
    private final AuditService auditService;

    public InsurerAccountController(InsurerAccountMapper accountMapper, InsurerAccountLinkMapper linkMapper,
                                     RechargeService rechargeService, AuditService auditService) {
        this.accountMapper = accountMapper;
        this.linkMapper = linkMapper;
        this.rechargeService = rechargeService;
        this.auditService = auditService;
    }

    public record InsurerAccountIn(String label, String bankName, String accountNo, String accountHolder) {}
    public record InsurerAccountUpdate(String label, String bankName, String accountNo, String accountHolder, String status) {}
    public record InsurerAccountLinkIn(String insurer, Integer accountId) {}

    @GetMapping("/insurer-accounts")
    public List<InsurerAccount> insurerAccounts(User user) {
        Rbac.requireRole(user, "仅总后台可管理收款账户", "admin");
        return accountMapper.findAll().stream().map(rechargeService::withInsurers).toList();
    }

    @PostMapping("/insurer-accounts")
    public InsurerAccount addInsurerAccount(@RequestBody InsurerAccountIn data, User user) {
        Rbac.requireRole(user, "仅总后台可管理收款账户", "admin");
        InsurerAccount item = new InsurerAccount();
        item.setLabel(data.label() == null ? "" : data.label());
        item.setBankName(data.bankName() == null ? "" : data.bankName());
        item.setAccountNo(data.accountNo() == null ? "" : data.accountNo());
        item.setAccountHolder(data.accountHolder() == null ? "" : data.accountHolder());
        item.setStatus("active");
        item.setCreatedAt(LocalDateTime.now());
        accountMapper.insert(item);
        auditService.log(user, "create", "insurer_account", String.valueOf(item.getId()));
        return rechargeService.withInsurers(item);
    }

    @PatchMapping("/insurer-accounts/{id}")
    public InsurerAccount updateInsurerAccount(@PathVariable int id, @RequestBody InsurerAccountUpdate data, User user) {
        Rbac.requireRole(user, "仅总后台可管理收款账户", "admin");
        InsurerAccount item = accountMapper.findById(id);
        if (item == null) throw ApiException.notFound("收款账户不存在");
        if (data.label() != null) item.setLabel(data.label());
        if (data.bankName() != null) item.setBankName(data.bankName());
        if (data.accountNo() != null) item.setAccountNo(data.accountNo());
        if (data.accountHolder() != null) item.setAccountHolder(data.accountHolder());
        if (data.status() != null) item.setStatus(data.status());
        accountMapper.update(item);
        auditService.log(user, "update", "insurer_account", String.valueOf(item.getId()));
        return rechargeService.withInsurers(item);
    }

    @GetMapping("/insurer-account-links")
    public List<InsurerAccountLink> insurerAccountLinks(User user) {
        Rbac.requireRole(user, "仅总后台可管理保司映射", "admin");
        return linkMapper.findAll();
    }

    @PostMapping("/insurer-account-links")
    public InsurerAccountLink addInsurerAccountLink(@RequestBody InsurerAccountLinkIn data, User user) {
        Rbac.requireRole(user, "仅总后台可管理保司映射", "admin");
        if (accountMapper.findById(data.accountId()) == null) throw ApiException.notFound("收款账户不存在");
        if (linkMapper.findByInsurer(data.insurer()) != null) throw ApiException.conflict("该保司已绑定收款账户，请先解绑旧映射");
        InsurerAccountLink item = new InsurerAccountLink();
        item.setInsurer(data.insurer());
        item.setAccountId(data.accountId());
        item.setCreatedAt(LocalDateTime.now());
        linkMapper.insert(item);
        auditService.log(user, "create", "insurer_account_link", String.valueOf(item.getId()));
        return item;
    }

    @DeleteMapping("/insurer-account-links/{id}")
    public Map<String, Boolean> deleteInsurerAccountLink(@PathVariable int id, User user) {
        Rbac.requireRole(user, "仅总后台可管理保司映射", "admin");
        InsurerAccountLink item = linkMapper.findById(id);
        if (item == null) throw ApiException.notFound("映射不存在");
        linkMapper.delete(id);
        auditService.log(user, "delete", "insurer_account_link", String.valueOf(id));
        return Map.of("ok", true);
    }
}
