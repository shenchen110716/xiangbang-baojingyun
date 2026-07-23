package com.xbb.identity.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByPhone(String phone);
    boolean existsByIdNumber(String idNumber);
}
