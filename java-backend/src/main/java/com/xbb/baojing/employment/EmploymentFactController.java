package com.xbb.baojing.employment;

import com.xbb.baojing.common.User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 用工事实的 fail-closed 只读镜像，对应 Python GET /api/employment-facts
 * (backend/routers/employment_facts.py::list_facts)。
 *
 * 只镜像“权威事实列表”这一条读取路径：范围过滤全部走 EmploymentFactAccess
 * （复用 Phase 1 的 EmployerScopeAccess），因此项目经理只看到授权用工单位、
 * admin 跨企业、其余角色 403，与 Python 逐字一致。两阶段导入、未匹配队列、
 * 修正与外部签名接入按 Phase 6 范围决策仍只在 Python 侧，不在此重实现。 */
@RestController
@RequestMapping("/api")
public class EmploymentFactController {
    private final EmploymentFactAccess access;

    public EmploymentFactController(EmploymentFactAccess access) {
        this.access = access;
    }

    @GetMapping("/employment-facts")
    public Map<String, List<EmploymentFact>> list(User user) {
        return Map.of("items", access.activeFacts(user));
    }
}
