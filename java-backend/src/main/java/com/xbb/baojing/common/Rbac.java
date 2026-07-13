package com.xbb.baojing.common;

import java.util.Arrays;

/** Ports backend/core/rbac.py — helpers called inline from services/controllers,
 * not annotations, matching the Python codebase's inline-check style exactly
 * (some endpoints check role directly instead of via a dependency, on purpose —
 * see PlanController.addPlan for the one place this distinction actually matters
 * for a test). */
public final class Rbac {
    private Rbac() {}

    public static void requireRole(User user, String detail, String... roles) {
        if (Arrays.stream(roles).noneMatch(r -> r.equals(user.getRole()))) {
            throw ApiException.forbidden(detail);
        }
    }

    public static void assertEnterpriseScope(User user, Integer enterpriseId) {
        assertEnterpriseScope(user, enterpriseId, "无权访问该单位数据");
    }

    public static void assertEnterpriseScope(User user, Integer enterpriseId, String detail) {
        if ("enterprise".equals(user.getRole()) && !user.getEnterpriseId().equals(enterpriseId)) {
            throw ApiException.forbidden(detail);
        }
    }

    public static void requireOperatorManager(User user) {
        if ("admin".equals(user.getRole())) return;
        if ("enterprise".equals(user.getRole()) && user.isOwner()) return;
        throw ApiException.forbidden("enterprise".equals(user.getRole()) ? "仅单位主管可管理操作员" : "无权管理操作员");
    }
}
