package com.xbb;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * outbox 事件的公共字段(@MappedSuperclass,不是表)。
 *
 * <p><b>为什么每个域自己一张表,而不是共享一张</b>——这是 Plan3 留下的问题,现在回答:
 * outbox 之所以可靠,全靠"**事件记录与业务数据在同一个事务里落库**"。
 * 本项目每个域有独立 DataSource,跨域就是跨事务:
 * 若放共享 schema,写业务数据和写 outbox 会落在两个事务里,可能一个成功一个失败,
 * outbox 的保证直接失效。所以约束本身就指向唯一答案——放发布方自己的 schema。
 *
 * <p>投递语义是**至少一次**:中继可能重投,所以消费方必须按 eventId 幂等
 * (§9.1"每个事件带 eventId,消费方按它去重")。
 */
@MappedSuperclass
public abstract class AbstractOutboxEvent {

    public enum Status { PENDING, PUBLISHED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, length = 64)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 200)
    private String eventType;

    @Column(name = "payload", nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "published_at")
    private Instant publishedAt;

    /** 到这个时刻之前不再重投(退避)。为空表示随时可投。 */
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    protected AbstractOutboxEvent() { }

    protected AbstractOutboxEvent(String eventId, String eventType, String payload) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.payload = payload;
    }

    public void markPublished(Instant at) {
        this.status = Status.PUBLISHED;
        this.publishedAt = at;
        this.lastError = null;
        this.nextAttemptAt = null;
    }

    /**
     * 投递失败:记次数与原因,**状态留在可重试**,并按退避推迟下一次。
     * 不把它置成终态——终态意味着放弃,而资金链路上的事件不该被悄悄放弃。
     */
    public void markAttemptFailed(String error, Instant nextAttemptAt) {
        this.attemptCount++;
        this.lastError = error != null && error.length() > 500 ? error.substring(0, 500) : error;
        this.status = Status.FAILED;
        this.nextAttemptAt = nextAttemptAt;
    }

    /**
     * 人工重放:清掉退避与失败计数,让它立刻重新排队。
     *
     * <p>不是"重新投递"——失败的行本来就一直在重试队列里。这里做的是
     * **确认问题已修复**:归零之后它不再被算作卡死事件,退避也从头开始。
     */
    public void resetForReplay() {
        this.attemptCount = 0;
        this.lastError = null;
        this.status = Status.PENDING;
        this.nextAttemptAt = null;
    }

    /** 重试到这个次数还没成功,就不是"下游抖了一下"了,需要人看一眼。 */
    public boolean isStuck(int threshold) {
        return status == Status.FAILED && attemptCount >= threshold;
    }

    public Long getId() { return id; }
    public String getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public Status getStatus() { return status; }
    public int getAttemptCount() { return attemptCount; }
    public String getLastError() { return lastError; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
}
