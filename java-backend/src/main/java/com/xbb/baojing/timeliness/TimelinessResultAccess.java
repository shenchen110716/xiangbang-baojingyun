package com.xbb.baojing.timeliness;

import com.xbb.baojing.common.User;
import com.xbb.baojing.enterprise.EmployerScopeAccess;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/** 及时率结果的 fail-closed 只读访问，镜像 Python 的 _scoped()
 * (backend/services/timeliness_reporting.py)。规则与用工事实一致：
 *
 * - 只有 status='current' 的记录进入正式口径；unmatched/conflict 停在数据质量
 *   队列，永不经此路径返回（§20.6，由 Mapper 的 WHERE status='current' 保证）。
 * - 范围来自 Phase 1 的 EmployerScopeAccess，不在此另立第二套鉴权。
 *
 * summary 的四类比率（反馈率、操作员归责率、超期/提前保费）是有状态的聚合业务
 * 逻辑，按 Phase 6 范围决策不在 Java 侧重实现；本类只提供两端共享的 scoped 读取，
 * 即 /summary 与 /details 共用的鉴权边界。 */
@Service
public class TimelinessResultAccess {
    private final EmploymentTimelinessResultMapper resultMapper;
    private final EmployerScopeAccess scopeAccess;

    public TimelinessResultAccess(EmploymentTimelinessResultMapper resultMapper, EmployerScopeAccess scopeAccess) {
        this.resultMapper = resultMapper;
        this.scopeAccess = scopeAccess;
    }

    public List<EmploymentTimelinessResult> currentResults(User user) {
        Set<Integer> allowed = scopeAccess.allowedEmployerIds(user);
        if (allowed != null && allowed.isEmpty()) {
            return List.of();
        }
        Integer enterpriseId = "enterprise".equals(user.getRole()) ? user.getEnterpriseId() : null;
        return resultMapper.findCurrentScoped(enterpriseId,
                allowed == null ? null : List.copyOf(allowed));
    }
}
