package com.xbb.baojing.employment;

import com.xbb.baojing.common.User;
import com.xbb.baojing.enterprise.EmployerScopeAccess;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/** Fail-closed employment-fact read access, mirroring Python's
 * active_facts() (backend/services/employment_facts.py). Two rules, both
 * load-bearing:
 *
 * - §20.6: only status='active' facts feed a published rate. pending_match,
 *   conflict, superseded and voided rows are real but must never reach a
 *   caller through this path — they belong in the data-quality queue instead.
 * - Employer scope comes from EmployerScopeAccess (Phase 1), never re-derived
 *   here — a second authorization path is how the two runtimes drift apart. */
@Service
public class EmploymentFactAccess {
    private final EmploymentFactMapper factMapper;
    private final EmployerScopeAccess scopeAccess;

    public EmploymentFactAccess(EmploymentFactMapper factMapper, EmployerScopeAccess scopeAccess) {
        this.factMapper = factMapper;
        this.scopeAccess = scopeAccess;
    }

    public List<EmploymentFact> activeFacts(User user) {
        // 唯一的 fail-closed 判定交给 Phase 1 的 EmployerScopeAccess：admin 与企业主管得 null
        // （企业范围），项目经理得授权用工单位集合，其余角色或未绑定投保单位直接 403。不要在这里
        // 再写第二套鉴权，那正是两端漂移的根源。
        Set<Integer> allowed = scopeAccess.allowedEmployerIds(user);
        if (allowed != null && allowed.isEmpty()) {
            // 空集是明确的“无授权范围”，不是“不过滤”。
            return List.of();
        }
        // 镜像 Python active_facts：企业过滤只在 role=='enterprise' 时施加；admin 跨企业读取。
        Integer enterpriseId = "enterprise".equals(user.getRole()) ? user.getEnterpriseId() : null;
        return factMapper.findActiveScoped(enterpriseId,
                allowed == null ? null : List.copyOf(allowed));
    }
}
