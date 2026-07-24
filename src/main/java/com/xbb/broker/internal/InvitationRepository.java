package com.xbb.broker.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InvitationRepository extends JpaRepository<Invitation, Long> {
    Optional<Invitation> findByWorkerUserId(long workerUserId);
}
