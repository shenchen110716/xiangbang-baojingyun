package com.xbb.baojing.finance;

import com.xbb.baojing.common.*;
import com.xbb.baojing.enterprise.Enterprise;
import com.xbb.baojing.enterprise.EnterpriseMapper;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class InvoiceController {
    private final InvoiceMapper invoiceMapper;
    private final EnterpriseMapper enterpriseMapper;
    private final AuditService auditService;

    public InvoiceController(InvoiceMapper invoiceMapper, EnterpriseMapper enterpriseMapper, AuditService auditService) {
        this.invoiceMapper = invoiceMapper;
        this.enterpriseMapper = enterpriseMapper;
        this.auditService = auditService;
    }

    public record InvoiceIn(int enterpriseId, String account, double amount, String title, String taxNo, String email) {}
    public record InvoiceUpdate(String status) {}

    private Invoice enrich(Invoice item) {
        Enterprise e = enterpriseMapper.findById(item.getEnterpriseId());
        item.setEnterpriseName(e != null ? e.getName() : "");
        return item;
    }

    @GetMapping("/invoices")
    public List<Invoice> list(User user) {
        Integer scoped;
        if ("enterprise".equals(user.getRole()) && user.getEnterpriseId() != null) scoped = user.getEnterpriseId();
        else if ("admin".equals(user.getRole())) scoped = null;
        else throw ApiException.forbidden("无权查看发票");
        return invoiceMapper.search(scoped).stream().map(this::enrich).toList();
    }

    @PostMapping("/invoices")
    public Invoice create(@RequestBody InvoiceIn data, User user) {
        Rbac.requireRole(user, "无权申请发票", "admin", "enterprise");
        Rbac.assertEnterpriseScope(user, data.enterpriseId(), "无权申请其他单位发票");
        if (enterpriseMapper.findById(data.enterpriseId()) == null) throw ApiException.notFound("投保单位不存在");
        Invoice item = new Invoice();
        item.setEnterpriseId(data.enterpriseId());
        item.setAccount(data.account() == null ? "premium" : data.account());
        item.setAmount(data.amount());
        item.setTitle(data.title());
        item.setTaxNo(data.taxNo() == null ? "" : data.taxNo());
        item.setEmail(data.email() == null ? "" : data.email());
        item.setCreatedAt(LocalDateTime.now());
        invoiceMapper.insert(item);
        auditService.log(user, "create", "invoice", String.valueOf(item.getId()), item.getAccount() + ":" + item.getAmount());
        return enrich(item);
    }

    @PatchMapping("/invoices/{id}")
    public Invoice update(@PathVariable int id, @RequestBody InvoiceUpdate data, User user) {
        Rbac.requireRole(user, "仅总后台可审核发票", "admin");
        Invoice item = invoiceMapper.findById(id);
        if (item == null) throw ApiException.notFound("发票申请不存在");
        if (!Set.of("pending", "approved", "issued", "rejected").contains(data.status())) throw ApiException.badRequest("状态不合法");
        item.setStatus(data.status());
        invoiceMapper.update(item);
        auditService.log(user, "status_change", "invoice", String.valueOf(id), data.status());
        return enrich(item);
    }
}
