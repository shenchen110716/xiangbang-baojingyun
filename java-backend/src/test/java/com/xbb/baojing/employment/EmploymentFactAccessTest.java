package com.xbb.baojing.employment;

import com.xbb.baojing.common.ApiException;
import com.xbb.baojing.common.User;
import com.xbb.baojing.enterprise.EmployerScopeAccess;
import com.xbb.baojing.enterprise.UserEmployerScopeMapper;
import com.xbb.baojing.enterprise.UserEmployerScope;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmploymentFactAccessTest {

    @Test
    void ownerSeesAllFactsWhileProjectManagerGetsOnlyAuthorizedEmployers() {
        EmploymentFactAccess access = new EmploymentFactAccess(
                new FactMapperStub(List.of(11)), new EmployerScopeAccess(new ScopeStub(List.of(11))));

        access.activeFacts(owner());   // must not throw; employer filter is null (enterprise-wide)
        List<EmploymentFact> managerFacts = access.activeFacts(projectManager());
        assertEquals(1, managerFacts.size());
    }

    /** admin 跨企业读取全部 active 事实，enterprise_id 过滤不施加（镜像 Python active_facts）。
     * 早期实现对 enterprise_id 为 null 一律 403，会把总后台一并挡掉。 */
    @Test
    void adminReadsAllActiveFactsAcrossEnterprises() {
        EmploymentFactAccess access = new EmploymentFactAccess(
                new FactMapperStub(List.of(11)), new EmployerScopeAccess(new ScopeStub(List.of())));

        User admin = new User();
        admin.setId(1);
        admin.setRole("admin");
        assertEquals(1, access.activeFacts(admin).size());
    }

    @Test
    void projectManagerWithNoActiveScopeSeesNothing() {
        EmploymentFactAccess access = new EmploymentFactAccess(
                new FactMapperStub(List.of(11)), new EmployerScopeAccess(new ScopeStub(List.of())));

        assertTrue(access.activeFacts(projectManager()).isEmpty());
    }

    /** MyBatis leaves the field untouched when enterprise_role is NULL; the
     * default must not resolve to enterprise-wide access (Phase 1 lesson). */
    @Test
    void userWithoutEnterpriseRoleFailsClosed() {
        EmploymentFactAccess access = new EmploymentFactAccess(
                new FactMapperStub(List.of(11)), new EmployerScopeAccess(new ScopeStub(List.of(11))));

        assertThrows(ApiException.class, () -> access.activeFacts(enterpriseUserWithNullRole()));
    }

    @Test
    void userWithNoEnterpriseIdFailsClosed() {
        EmploymentFactAccess access = new EmploymentFactAccess(
                new FactMapperStub(List.of(11)), new EmployerScopeAccess(new ScopeStub(List.of(11))));

        User user = new User();
        user.setId(7);
        user.setRole("enterprise");
        user.setEnterpriseRole("owner");
        assertThrows(ApiException.class, () -> access.activeFacts(user));
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

    private static User enterpriseUserWithNullRole() {
        return enterpriseUser();
    }

    private static User enterpriseUser() {
        User user = new User();
        user.setId(7);
        user.setRole("enterprise");
        user.setEnterpriseId(3);
        return user;
    }

    private record FactMapperStub(List<Integer> employerIds) implements EmploymentFactMapper {
        @Override
        public EmploymentFact findById(Integer id) {
            return null;
        }

        @Override
        public List<EmploymentFact> findActiveScoped(Integer enterpriseId, List<Integer> employerIds) {
            EmploymentFact fact = new EmploymentFact();
            fact.setId(1);
            fact.setEnterpriseId(enterpriseId);
            fact.setActualEmployerId(this.employerIds.get(0));
            return List.of(fact);
        }
    }

    private record ScopeStub(List<Integer> ids) implements UserEmployerScopeMapper {
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
