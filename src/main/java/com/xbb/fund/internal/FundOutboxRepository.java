package com.xbb.fund.internal;

import com.xbb.AbstractOutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface FundOutboxRepository extends JpaRepository<FundOutboxEvent, Long> {

    List<FundOutboxEvent> findByStatusInOrderByIdAsc(List<AbstractOutboxEvent.Status> statuses);

    /**
     * 取一批待投递的行并**锁住**。
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} 不是可选的:中继是定时任务,多实例部署时
     * 两个实例会同时扫到同一行、同时投递,消费方的"先查后插"幂等就会撞进竞态窗口
     * (先查都说没有,然后两个都插)。加锁之后一行同一时刻只可能被一个中继处理,
     * 拿不到锁的直接跳过、下一轮再来,而不是排队等。
     *
     * <p>LIMIT 一批:积压很多时不该把它们塞进同一个事务,那会长时间占着连接和锁。
     */
    @Query(value = """
            SELECT * FROM fund.outbox_event
            WHERE status <> 'PUBLISHED'
            ORDER BY id
            LIMIT 100
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<FundOutboxEvent> lockPendingBatch();
}
