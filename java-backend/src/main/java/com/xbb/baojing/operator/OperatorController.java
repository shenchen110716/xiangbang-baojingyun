package com.xbb.baojing.operator;

import com.xbb.baojing.common.*;
import com.xbb.baojing.enterprise.Enterprise;
import com.xbb.baojing.enterprise.EnterpriseMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api")
public class OperatorController {
    private final UserMapper userMapper;
    private final EnterpriseMapper enterpriseMapper;
    private final AuditService auditService;
    private final PasswordEncoder passwordEncoder;

    public OperatorController(UserMapper userMapper, EnterpriseMapper enterpriseMapper, AuditService auditService, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.enterpriseMapper = enterpriseMapper;
        this.auditService = auditService;
        this.passwordEncoder = passwordEncoder;
    }

    public record OperatorIn(String username, String password, String name, String phone, Integer enterpriseId) {}
    public record OperatorUpdate(String name, String phone, String password, Boolean active, Integer enterpriseId) {}
    public record OperatorOut(int id, String username, String name, String phone, String role, Integer enterpriseId,
                               String enterpriseName, boolean isOwner, boolean active, java.time.LocalDateTime createdAt) {}

    private OperatorOut toOut(User item) {
        Enterprise e = item.getEnterpriseId() != null ? enterpriseMapper.findById(item.getEnterpriseId()) : null;
        return new OperatorOut(item.getId(), item.getUsername(), item.getName(), item.getPhone(), item.getRole(),
                item.getEnterpriseId(), e != null ? e.getName() : "", item.isOwner(), item.isActive(), item.getCreatedAt());
    }

    @GetMapping("/operators")
    public List<OperatorOut> list(User user) {
        Integer scoped;
        if ("enterprise".equals(user.getRole())) {
            if (user.getEnterpriseId() == null) return List.of();
            scoped = user.getEnterpriseId();
        } else if ("admin".equals(user.getRole())) {
            scoped = null;
        } else {
            throw ApiException.forbidden("无权查看操作员");
        }
        return userMapper.findOperators(scoped).stream().map(this::toOut).toList();
    }

    @PostMapping("/operators")
    public OperatorOut create(@RequestBody OperatorIn data, User user) {
        Rbac.requireOperatorManager(user);
        Integer enterpriseId = "enterprise".equals(user.getRole()) ? user.getEnterpriseId() : data.enterpriseId();
        if (enterpriseId == null || enterpriseMapper.findById(enterpriseId) == null) throw ApiException.badRequest("请选择有效投保单位");
        if (userMapper.countByUsername(data.username()) > 0) throw ApiException.conflict("登录账号已存在");
        User item = new User();
        item.setUsername(data.username().strip());
        item.setPasswordHash(passwordEncoder.encode(data.password()));
        item.setName(data.name().strip());
        item.setPhone(data.phone() == null ? "" : data.phone().strip());
        item.setRole("enterprise");
        item.setEnterpriseId(enterpriseId);
        item.setOwner(false);
        item.setActive(true);
        item.setCreatedAt(LocalDateTime.now());
        userMapper.insert(item);
        auditService.log(user, "create", "operator", String.valueOf(item.getId()));
        return toOut(item);
    }

    @PatchMapping("/operators/{id}")
    public OperatorOut update(@PathVariable int id, @RequestBody OperatorUpdate data, User user) {
        User item = userMapper.findById(id);
        if (item == null || !"enterprise".equals(item.getRole())) throw ApiException.notFound("操作员不存在");
        if ("enterprise".equals(user.getRole())) {
            if (!user.isOwner()) throw ApiException.forbidden("仅单位主管可管理操作员");
            if (!item.getEnterpriseId().equals(user.getEnterpriseId())) throw ApiException.forbidden("无权管理其他单位操作员");
        } else if (!"admin".equals(user.getRole())) {
            throw ApiException.forbidden("无权管理操作员");
        }
        if (item.getId().equals(user.getId()) && Boolean.FALSE.equals(data.active())) throw ApiException.badRequest("不能停用当前登录账号");
        if (item.isOwner() && Boolean.FALSE.equals(data.active())) throw ApiException.badRequest("单位主管不能停用");
        if (data.enterpriseId() != null) {
            if (!"admin".equals(user.getRole())) throw ApiException.forbidden("仅平台端可调整所属单位");
            if (item.isOwner()) throw ApiException.badRequest("单位主管不能更换所属单位");
            Enterprise target = enterpriseMapper.findById(data.enterpriseId());
            if (target == null) throw ApiException.badRequest("目标投保单位不存在");
            item.setEnterpriseId(target.getId());
        }
        if (data.name() != null) item.setName(data.name().strip());
        if (data.phone() != null) item.setPhone(data.phone().strip());
        if (data.password() != null && !data.password().isBlank()) {
            item.setPasswordHash(passwordEncoder.encode(data.password()));
            item.setSessionVersion(item.getSessionVersion() + 1);
        }
        if (data.active() != null) {
            item.setActive(data.active());
            item.setStatus(item.isActive() ? "active" : "inactive");
            if (!item.isActive()) item.setSessionVersion(item.getSessionVersion() + 1);
        }
        userMapper.update(item);
        auditService.log(user, "update", "operator", String.valueOf(item.getId()));
        return toOut(item);
    }
}
