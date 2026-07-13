package com.xbb.baojing.enterprise;

import com.xbb.baojing.common.ApiException;
import com.xbb.baojing.common.AuditService;
import com.xbb.baojing.common.User;
import com.xbb.baojing.position.WorkPositionMapper;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class ActualEmployerController {
    private final ActualEmployerMapper mapper;
    private final EnterpriseMapper enterpriseMapper;
    private final WorkPositionMapper positionMapper;
    private final AuditService auditService;

    public ActualEmployerController(ActualEmployerMapper mapper, EnterpriseMapper enterpriseMapper,
                                     WorkPositionMapper positionMapper, AuditService auditService) {
        this.mapper = mapper;
        this.enterpriseMapper = enterpriseMapper;
        this.positionMapper = positionMapper;
        this.auditService = auditService;
    }

    public record ActualEmployerIn(Integer enterpriseId, String name, String creditCode, String contact, String phone) {}
    public record ActualEmployerUpdate(String name, String creditCode, String contact, String phone) {}

    @GetMapping("/actual-employers")
    public List<ActualEmployer> list(User user) {
        Integer scoped = "enterprise".equals(user.getRole()) && user.getEnterpriseId() != null ? user.getEnterpriseId() : null;
        return mapper.search(scoped);
    }

    @PostMapping("/actual-employers")
    public ActualEmployer create(@RequestBody ActualEmployerIn data, User user) {
        Integer eid = "enterprise".equals(user.getRole()) ? user.getEnterpriseId() : data.enterpriseId();
        if (eid == null || enterpriseMapper.findById(eid) == null) throw ApiException.badRequest("请指定有效投保单位");
        ActualEmployer e = new ActualEmployer();
        e.setEnterpriseId(eid);
        e.setName(data.name());
        if (data.creditCode() != null) e.setCreditCode(data.creditCode());
        if (data.contact() != null) e.setContact(data.contact());
        if (data.phone() != null) e.setPhone(data.phone());
        e.setCreatedAt(LocalDateTime.now());
        mapper.insert(e);
        auditService.log(user, "create", "actual_employer", String.valueOf(e.getId()));
        return e;
    }

    @PatchMapping("/actual-employers/{id}")
    public ActualEmployer update(@PathVariable int id, @RequestBody ActualEmployerUpdate data, User user) {
        ActualEmployer e = mapper.findById(id);
        if (e == null) throw ApiException.notFound("实际工作单位不存在");
        if ("enterprise".equals(user.getRole()) && !user.getEnterpriseId().equals(e.getEnterpriseId())) throw ApiException.forbidden("无权操作");
        if (!user.getRole().equals("admin") && !user.getRole().equals("enterprise")) throw ApiException.forbidden("无权操作");
        if (data.name() != null) e.setName(data.name().strip());
        if (data.creditCode() != null) e.setCreditCode(data.creditCode().strip());
        if (data.contact() != null) e.setContact(data.contact().strip());
        if (data.phone() != null) e.setPhone(data.phone().strip());
        mapper.update(e);
        auditService.log(user, "update", "actual_employer", String.valueOf(id));
        return e;
    }

    @DeleteMapping("/actual-employers/{id}")
    public Map<String, Boolean> delete(@PathVariable int id, User user) {
        ActualEmployer e = mapper.findById(id);
        if (e == null) throw ApiException.notFound("实际工作单位不存在");
        if ("enterprise".equals(user.getRole()) && !user.getEnterpriseId().equals(e.getEnterpriseId())) throw ApiException.forbidden("无权操作");
        if (!user.getRole().equals("admin") && !user.getRole().equals("enterprise")) throw ApiException.forbidden("无权操作");
        if (mapper.countPositions(id) > 0) throw ApiException.conflict("该工作单位已关联岗位，不能删除；可先暂停使用");
        mapper.delete(id);
        auditService.log(user, "delete", "actual_employer", String.valueOf(id));
        return Map.of("ok", true);
    }

    @PatchMapping("/actual-employers/{id}/status")
    public ActualEmployer setStatus(@PathVariable int id, @RequestParam("status") String status, User user) {
        if (!Set.of("active", "paused").contains(status)) throw ApiException.badRequest("状态不合法");
        ActualEmployer e = mapper.findById(id);
        if (e == null) throw ApiException.notFound("实际用工单位不存在");
        if ("enterprise".equals(user.getRole()) && !user.getEnterpriseId().equals(e.getEnterpriseId())) throw ApiException.forbidden("无权操作");
        e.setStatus(status);
        mapper.update(e);
        auditService.log(user, "status_change", "actual_employer", String.valueOf(id), status);
        return e;
    }
}
