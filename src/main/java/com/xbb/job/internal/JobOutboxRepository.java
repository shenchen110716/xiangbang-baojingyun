package com.xbb.job.internal;

import com.xbb.OutboxEventRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface JobOutboxRepository extends OutboxEventRepository<JobOutboxEvent> {

    /** 取一批待投递的行并锁住,理由见 AbstractOutboxRelay 与 fund 的同名方法。 */
    @Query(value = """
            SELECT * FROM job.outbox_event
            WHERE status <> 'PUBLISHED'
              AND (next_attempt_at IS NULL OR next_attempt_at <= now())
            ORDER BY id
            LIMIT 100
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<JobOutboxEvent> lockPendingBatch();
}
