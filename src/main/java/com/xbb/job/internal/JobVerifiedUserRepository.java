package com.xbb.job.internal;

import org.springframework.data.jpa.repository.JpaRepository;

// 显式改名:org 域也有个同名的 VerifiedUserRepository,默认 bean 名会撞车
public interface JobVerifiedUserRepository extends JpaRepository<VerifiedUser, Long> { }
