package com.xbb;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * 各域 outbox 仓库的公共父接口。
 *
 * <p>取待投递那批仍由各域自己写原生 SQL——`FOR UPDATE SKIP LOCKED` 要写死表名。
 * 但运维要用的这两个查询可以泛化:`#{#entityName}` 让 JPQL 在每个子接口里
 * 各自绑到自己的实体上。
 */
@NoRepositoryBean
public interface OutboxEventRepository<T extends AbstractOutboxEvent> extends JpaRepository<T, Long> {

    /** 卡死的事件:重试到阈值还没成功的。 */
    @Query("select e from #{#entityName} e where e.status = :status and e.attemptCount >= :threshold order by e.id")
    List<T> findStuck(AbstractOutboxEvent.Status status, int threshold);

    Optional<T> findByEventId(String eventId);
}
