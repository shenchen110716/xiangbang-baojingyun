package com.xbb.broker.internal;

import org.springframework.data.jpa.repository.JpaRepository;

// 显式改名:identity/org/job 域各自也有个 VerifiedUserRepository,默认 bean 名会撞车
public interface BrokerVerifiedUserRepository extends JpaRepository<VerifiedUser, Long> { }
