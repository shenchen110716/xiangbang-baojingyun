package com.xbb.ops.internal;

import jakarta.persistence.*;

/**
 * 行政区划字典(国标 GB/T 2260)。
 *
 * <p>只读参考数据 —— 由迁移种下,没有写入口。
 * 要加地区就加一条迁移,<b>加之前核对国标码</b>:错一个码就是那个地区的佣金按别处算。
 */
@Entity
@Table(name = "region", schema = "ops")
class Region {

    @Id
    @Column(length = 6)
    private String code;

    @Column(nullable = false, length = 50)
    private String name;

    /** 省级为 null。 */
    @Column(name = "parent_code", length = 6)
    private String parentCode;

    @Column(nullable = false)
    private short level;

    protected Region() { }

    String getCode() { return code; }
    String getName() { return name; }
    String getParentCode() { return parentCode; }
    short getLevel() { return level; }
}
