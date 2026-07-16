package com.xbb.baojing.enterprise;

import com.xbb.baojing.common.ApiException;
import com.xbb.baojing.common.User;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;

/** Fail-closed employer scope gate for Java parity endpoints. */
@Service
public class EmployerScopeAccess {
    private final UserEmployerScopeMapper mapper;

    public EmployerScopeAccess(UserEmployerScopeMapper mapper) {
        this.mapper = mapper;
    }

    /** null denotes an enterprise-wide principal; an empty set is deliberately no access. */
    public Set<Integer> allowedEmployerIds(User user) {
        if ("admin".equals(user.getRole())) return null;
        if (!"enterprise".equals(user.getRole()) || user.getEnterpriseId() == null) {
            throw ApiException.forbidden("无权访问投保单位数据");
        }
        if (user.isOwner() || "owner".equals(user.getEnterpriseRole())) return null;
        if (!"project_manager".equals(user.getEnterpriseRole())) {
            throw ApiException.forbidden("无权访问投保单位数据");
        }
        return new LinkedHashSet<>(mapper.findActiveEmployerIds(user.getId(), user.getEnterpriseId()));
    }

    public void requireEmployerAccess(User user, Integer actualEmployerId) {
        Set<Integer> ids = allowedEmployerIds(user);
        if (ids != null && (actualEmployerId == null || !ids.contains(actualEmployerId))) {
            throw ApiException.forbidden("无权访问该实际用工单位");
        }
    }
}
