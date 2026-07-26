package com.xbb.profile.internal;

import com.xbb.OutboxEventRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ProfileOutboxRepository extends OutboxEventRepository<ProfileOutboxEvent> {

    /** 取一批待投递的行并锁住,理由见 AbstractOutboxRelay。 */
    @Query(value = """
            SELECT * FROM profile.outbox_event
            WHERE status <> 'PUBLISHED'
              AND (next_attempt_at IS NULL OR next_attempt_at <= now())
            ORDER BY id
            LIMIT 100
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<ProfileOutboxEvent> lockPendingBatch();
}
