package com.xbb.identity.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface IdentityOutboxRepository extends JpaRepository<IdentityOutboxEvent, Long> {

    /** 取一批待投递的行并锁住,理由见 AbstractOutboxRelay 与 fund 的同名方法。 */
    @Query(value = """
            SELECT * FROM identity.outbox_event
            WHERE status <> 'PUBLISHED'
            ORDER BY id
            LIMIT 100
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<IdentityOutboxEvent> lockPendingBatch();
}
