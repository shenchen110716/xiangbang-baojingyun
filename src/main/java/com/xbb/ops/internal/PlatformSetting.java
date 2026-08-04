package com.xbb.ops.internal;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 一个平台参数。**主键就是 key**,天然保证一个键只有一行。
 *
 * <p>老系统那边是一张 sys_config 宽表 + `limit 1` 取任意一行,
 * 表里有多行时取哪行不确定。这里换成键值行,不确定性从根上消掉。
 */
@Entity
@Table(name = "platform_setting", schema = "ops")
public class PlatformSetting {

    public enum ValueType { INT, DECIMAL, BOOLEAN, STRING }

    @Id
    @Column(name = "setting_key", nullable = false, length = 80)
    private String key;

    @Column(nullable = false, length = 200)
    private String value;

    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", nullable = false, length = 16)
    private ValueType valueType;

    @Column(nullable = false, length = 32)
    private String category;

    @Column(nullable = false, length = 60)
    private String label;

    @Column(length = 300)
    private String description;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "updated_by")
    private Long updatedBy;

    @Version
    private long version;

    protected PlatformSetting() { }

    public String getKey() { return key; }
    public String getValue() { return value; }
    public ValueType getValueType() { return valueType; }
    public String getCategory() { return category; }
    public String getLabel() { return label; }
    public String getDescription() { return description; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getUpdatedBy() { return updatedBy; }

    /** 改值。类型校验在服务层做——实体不该知道"什么样的字符串算合法的百分比"。 */
    void changeTo(String newValue, long operatorUserId) {
        this.value = newValue;
        this.updatedBy = operatorUserId;
        this.updatedAt = Instant.now();
    }
}
