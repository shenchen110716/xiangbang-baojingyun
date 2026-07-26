package com.xbb.talent.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface CountedEngagementRepository extends JpaRepository<CountedEngagement, Long> {

    /**
     * 认领这一单的计数权;已被认领则返回 0。
     *
     * <p>**不能用"先 existsById 再 save"**:本实体的主键是手工赋的,
     * Spring Data 的 {@code save()} 对非空主键走 {@code merge()},那是 upsert——
     * 永远不会撞主键。于是两个并发的投递(多实例中继、或多个测试上下文)
     * 会双双通过 existsById 检查、双双"插入"成功,各自再把履约次数 +1。
     * 靠 {@code ON CONFLICT DO NOTHING} 让数据库来裁决,只有一个人拿到 1。
     */
    @Modifying
    @Query(value = """
            INSERT INTO talent.counted_engagement (application_id, user_id)
            VALUES (:applicationId, :userId)
            ON CONFLICT (application_id) DO NOTHING
            """, nativeQuery = true)
    int claim(long applicationId, long userId);
}
