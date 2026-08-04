package com.xbb.attendance.internal;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 考勤变更留痕。考勤直接决定发多少钱,
 * "谁把这天的工时从 8 小时改成 12 小时"事后必须查得到。
 *
 * <p>老系统靠 {@code isRecount} 一个布尔位表示"需要重算" ——
 * 既说不出改了什么,也说不出是谁改的。
 */
@Entity
@Table(name = "workday_change", schema = "attendance")
public class WorkdayChange {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workday_id", nullable = false)
    private long workdayId;

    @Column(nullable = false, length = 32)
    private String field;

    @Column(name = "old_value", length = 64)
    private String oldValue;

    @Column(name = "new_value", length = 64)
    private String newValue;

    @Column(name = "changed_by")
    private Long changedBy;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt = Instant.now();

    @Column(length = 200)
    private String reason;

    protected WorkdayChange() { }

    WorkdayChange(long workdayId, String field, String oldValue, String newValue,
                  Long changedBy, String reason) {
        this.workdayId = workdayId;
        this.field = field;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.changedBy = changedBy;
        this.reason = reason;
    }

    public Long getId() { return id; }
    public long getWorkdayId() { return workdayId; }
    public String getField() { return field; }
    public String getOldValue() { return oldValue; }
    public String getNewValue() { return newValue; }
    public Long getChangedBy() { return changedBy; }
    public Instant getChangedAt() { return changedAt; }
    public String getReason() { return reason; }
}
