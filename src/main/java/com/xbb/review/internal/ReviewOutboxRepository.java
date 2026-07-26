package com.xbb.review.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ReviewOutboxRepository extends JpaRepository<ReviewOutboxEvent, Long> {

    /** 取一批待投递的行并锁住,理由见 AbstractOutboxRelay。 */
    @Query(value = """
            SELECT * FROM review.outbox_event
            WHERE status <> 'PUBLISHED'
            ORDER BY id
            LIMIT 100
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<ReviewOutboxEvent> lockPendingBatch();
}
