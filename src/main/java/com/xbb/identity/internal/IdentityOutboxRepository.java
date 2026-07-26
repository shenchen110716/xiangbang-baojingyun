package com.xbb.identity.internal;

import com.xbb.OutboxEventRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface IdentityOutboxRepository extends OutboxEventRepository<IdentityOutboxEvent> {

    /** 取一批待投递的行并锁住,理由见 AbstractOutboxRelay 与 fund 的同名方法。 */
    @Query(value = """
            SELECT * FROM identity.outbox_event
            WHERE status <> 'PUBLISHED'
              AND (next_attempt_at IS NULL OR next_attempt_at <= now())
            ORDER BY id
            LIMIT 100
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<IdentityOutboxEvent> lockPendingBatch();
}
