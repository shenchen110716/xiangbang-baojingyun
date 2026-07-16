package com.xbb.baojing.enterprise;

import com.xbb.baojing.common.ApiException;
import com.xbb.baojing.common.User;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EmployerScopeAccessTest {

    @Test
    void ownerKeepsEnterpriseWideAccessWhileProjectManagerGetsOnlyActiveScopes() {
        EmployerScopeAccess access = new EmployerScopeAccess(new ScopeMapperStub(List.of(11)));

        assertNull(access.allowedEmployerIds(owner()));
        assertEquals(Set.of(11), access.allowedEmployerIds(projectManager()));
    }

    @Test
    void projectManagerWithNoActiveScopeFailsClosed() {
        EmployerScopeAccess access = new EmployerScopeAccess(new ScopeMapperStub(List.of()));

        assertEquals(Set.of(), access.allowedEmployerIds(projectManager()));
        assertThrows(ApiException.class, () -> access.requireEmployerAccess(projectManager(), 11));
    }

    /** MyBatis leaves the field untouched when enterprise_role is NULL, so the
     *  default must not resolve to enterprise-wide access. */
    @Test
    void enterpriseUserWithoutEnterpriseRoleFailsClosed() {
        EmployerScopeAccess access = new EmployerScopeAccess(new ScopeMapperStub(List.of(11)));

        assertThrows(ApiException.class, () -> access.allowedEmployerIds(enterpriseUser()));
        assertThrows(ApiException.class, () -> access.requireEmployerAccess(enterpriseUser(), 11));
    }

    private static User owner() {
        User user = enterpriseUser();
        user.setEnterpriseRole("owner");
        return user;
    }

    private static User projectManager() {
        User user = enterpriseUser();
        user.setEnterpriseRole("project_manager");
        return user;
    }

    private static User enterpriseUser() {
        User user = new User();
        user.setId(7);
        user.setRole("enterprise");
        user.setEnterpriseId(3);
        return user;
    }

    private record ScopeMapperStub(List<Integer> ids) implements UserEmployerScopeMapper {
        @Override
        public List<Integer> findActiveEmployerIds(Integer userId, Integer enterpriseId) {
            return ids;
        }

        @Override
        public UserEmployerScope findPrimaryManagerAt(Integer actualEmployerId, LocalDateTime occurredAt) {
            return null;
        }
    }
}
