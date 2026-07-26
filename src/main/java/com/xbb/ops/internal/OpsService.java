package com.xbb.ops.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbb.ops.api.OpsApi;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
class OpsService implements OpsApi {

    private final DictionaryItemRepository items;
    private final AgreementTemplateRepository templates;
    private final ObjectMapper json;

    OpsService(DictionaryItemRepository items, AgreementTemplateRepository templates, ObjectMapper json) {
        this.items = items;
        this.templates = templates;
        this.json = json;
    }

    @Override
    @Transactional("opsTransactionManager")
    public long addItem(String dictType, String key, String value, int sortOrder) {
        return addItem(dictType, key, value, sortOrder, null);
    }

    @Override
    @Transactional("opsTransactionManager")
    public long addItem(String dictType, String key, String value, int sortOrder,
                        Map<String, String> attributes) {
        DictionaryItem item = new DictionaryItem(dictType, key, value, sortOrder);
        item.setAttributes(serialize(attributes));
        return items.save(item).getId();
    }

    @Override
    @Transactional("opsTransactionManager")
    public void updateAttributes(long itemId, Map<String, String> attributes) {
        DictionaryItem item = require(itemId);
        item.setAttributes(serialize(attributes));
        items.save(item);
    }

    @Override
    @Transactional("opsTransactionManager")
    public void updateValue(long itemId, String value) {
        DictionaryItem item = require(itemId);
        item.setValue(value);
        items.save(item);
    }

    @Override
    @Transactional("opsTransactionManager")
    public void disableItem(long itemId) {
        setEnabled(itemId, false);
    }

    @Override
    @Transactional("opsTransactionManager")
    public void enableItem(long itemId) {
        setEnabled(itemId, true);
    }

    private void setEnabled(long itemId, boolean enabled) {
        DictionaryItem item = require(itemId);
        item.setEnabled(enabled);
        items.save(item);
    }

    private DictionaryItem require(long itemId) {
        return items.findById(itemId).orElseThrow(() -> new IllegalArgumentException("字典项不存在"));
    }

    @Override
    @Transactional(transactionManager = "opsTransactionManager", readOnly = true)
    public List<DictItemView> itemsOf(String dictType) {
        return items.findByDictTypeAndEnabledTrueOrderBySortOrderAsc(dictType).stream()
                .map(this::toView).toList();
    }

    @Override
    @Transactional(transactionManager = "opsTransactionManager", readOnly = true)
    public Optional<DictItemView> findItem(String dictType, String key) {
        return items.findByDictTypeAndKey(dictType, key).map(this::toView);
    }

    @Override
    @Transactional("opsTransactionManager")
    public int publishTemplate(String templateKey, String body) {
        // 上一版先下架并立即刷库:表上有"同 key 至多一版生效"的部分唯一索引,
        // 不先落这一步,插入新版时会直接撞约束。
        templates.findByTemplateKeyAndActiveTrue(templateKey).ifPresent(current -> {
            current.retire();
            templates.saveAndFlush(current);
        });
        int nextVersion = templates.findFirstByTemplateKeyOrderByVersionDesc(templateKey)
                .map(latest -> latest.getVersion() + 1)
                .orElse(1);
        templates.save(new AgreementTemplateRecord(templateKey, nextVersion, body));
        return nextVersion;
    }

    @Override
    @Transactional(transactionManager = "opsTransactionManager", readOnly = true)
    public Optional<AgreementTemplateView> activeTemplate(String templateKey) {
        return templates.findByTemplateKeyAndActiveTrue(templateKey).map(OpsService::toView);
    }

    @Override
    @Transactional(transactionManager = "opsTransactionManager", readOnly = true)
    public Optional<AgreementTemplateView> templateVersion(String templateKey, int version) {
        return templates.findByTemplateKeyAndVersion(templateKey, version).map(OpsService::toView);
    }

    private DictItemView toView(DictionaryItem i) {
        return new DictItemView(i.getId(), i.getDictType(), i.getKey(), i.getValue(),
                i.getSortOrder(), i.isEnabled(), deserialize(i.getAttributes()));
    }

    private static AgreementTemplateView toView(AgreementTemplateRecord t) {
        return new AgreementTemplateView(t.getId(), t.getTemplateKey(), t.getVersion(),
                t.getBody(), t.isActive());
    }

    private String serialize(Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return null;
        }
        try {
            return json.writeValueAsString(attributes);
        } catch (Exception e) {
            throw new IllegalArgumentException("字典属性无法序列化", e);
        }
    }

    private Map<String, String> deserialize(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(raw, new TypeReference<Map<String, String>>() { });
        } catch (Exception e) {
            // 属性直接参与算分,坏数据静默当成空属性会让扣分凭空消失——宁可炸,不要悄悄算错。
            throw new IllegalStateException("字典项属性 JSON 损坏: " + raw, e);
        }
    }
}
