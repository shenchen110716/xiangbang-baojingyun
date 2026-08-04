package com.xbb.ops.internal;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 参数改动留痕。
 *
 * <p>这些参数直接决定分给谁多少钱。「谁在什么时候把主动佣金从 60% 改成 40%」
 * 事后必须查得到——老系统改了就是改了,没有任何痕迹,出纠纷时只能靠人回忆。
 */
@Entity
@Table(name = "platform_setting_change", schema = "ops")
public class PlatformSettingChange {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "setting_key", nullable = false, length = 80)
    private String key;

    @Column(name = "old_value", length = 200)
    private String oldValue;

    @Column(name = "new_value", nullable = false, length = 200)
    private String newValue;

    @Column(name = "changed_by", nullable = false)
    private long changedBy;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt = Instant.now();

    @Column(length = 200)
    private String reason;

    protected PlatformSettingChange() { }

    PlatformSettingChange(String key, String oldValue, String newValue, long changedBy, String reason) {
        this.key = key;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.changedBy = changedBy;
        this.reason = reason;
    }

    public Long getId() { return id; }
    public String getKey() { return key; }
    public String getOldValue() { return oldValue; }
    public String getNewValue() { return newValue; }
    public long getChangedBy() { return changedBy; }
    public Instant getChangedAt() { return changedAt; }
    public String getReason() { return reason; }
}
