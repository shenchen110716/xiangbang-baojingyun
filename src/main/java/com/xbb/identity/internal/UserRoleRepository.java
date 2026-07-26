package com.xbb.identity.internal;

import com.xbb.identity.api.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserRoleRepository extends JpaRepository<UserRole, UserRole.Key> {

    List<UserRole> findByUserId(long userId);

    boolean existsByUserIdAndRole(long userId, Role role);

    void deleteByUserIdAndRole(long userId, Role role);
}
