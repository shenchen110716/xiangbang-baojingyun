package com.xbb.ops.internal;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "dictionary_item", schema = "ops")
public class DictionaryItem {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dict_type", nullable = false, length = 50)
    private String dictType;

    @Column(name = "item_key", nullable = false, length = 100)
    private String key;

    @Column(name = "item_value", nullable = false, length = 200)
    private String value;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected DictionaryItem() { }

    public DictionaryItem(String dictType, String key, String value, int sortOrder) {
        this.dictType = dictType;
        this.key = key;
        this.value = value;
        this.sortOrder = sortOrder;
    }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Long getId() { return id; }
    public String getDictType() { return dictType; }
    public String getKey() { return key; }
    public String getValue() { return value; }
    public int getSortOrder() { return sortOrder; }
    public boolean isEnabled() { return enabled; }
}
