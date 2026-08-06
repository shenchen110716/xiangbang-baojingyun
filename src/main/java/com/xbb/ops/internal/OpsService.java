package com.xbb.ops.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbb.ops.api.OpsApi;
import com.xbb.identity.api.IdentityApi;
import com.xbb.identity.api.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
class OpsService implements OpsApi {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OpsService.class);

    /**
     * 参数缓存的存活时间。
     *
     * <p>参数在信用分、匹配打分这类热路径上被反复读,每次查库不划算。
     * 但缓存意味着**改动不是立刻全局生效**:多实例部署时,别的实例最多晚这么久看到新值。
     * 60 秒是取舍——运营改完等一分钟可以接受,而热路径省掉的查询是每次请求都有的。
     */
    /**
     * 参数缓存有效期。**每个应用实例(以及测试里每个 Spring 上下文)各有一份缓存**,
     * updateSetting 只失效**本实例**的那份 —— 所以改一个参数,别的实例最多要等这么久才生效。
     *
     * <p>做成可配是因为测试里那个延迟会变成假失败:测试用一个上下文改参数,
     * 事件却由另一个上下文处理,后者读到的还是旧值。测试里设 0(见 application.properties)。
     *
     * <p>生产上保留 60 秒:参数读取在分账主路径上,每次都查库不值当。
     */
    private final Duration settingTtl;

    private final DictionaryItemRepository items;
    /** 行政区划字典。只读,由迁移种下。 */
    private final RegionRepository regions;
    private final AgreementTemplateRepository templates;
    private final PlatformSettingRepository settings;
    private final PlatformSettingChangeRepository settingChanges;
    private final IdentityApi identityApi;
    private final ObjectMapper json;

    private volatile java.util.Map<String, String> settingCache = java.util.Map.of();
    private volatile Instant cacheLoadedAt = Instant.EPOCH;

    OpsService(DictionaryItemRepository items, AgreementTemplateRepository templates,
               PlatformSettingRepository settings, PlatformSettingChangeRepository settingChanges,
               IdentityApi identityApi, RegionRepository regions, ObjectMapper json,
               @org.springframework.beans.factory.annotation.Value(
                       "${xbb.ops.setting-cache-ttl-ms:60000}") long ttlMs) {
        this.regions = regions;
        this.items = items;
        this.templates = templates;
        this.settings = settings;
        this.settingChanges = settingChanges;
        this.identityApi = identityApi;
        this.json = json;
        this.settingTtl = Duration.ofMillis(ttlMs);
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

    // ─────────────── 平台参数 ───────────────

    @Override
    @Transactional(transactionManager = "opsTransactionManager", readOnly = true)
    public long settingInt(String key, long fallback) {
        String v = rawSetting(key);
        if (v == null) {
            return fallback;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            // 值存坏了就退回兜底,并**吼一声**。静默用兜底会让"改了没生效"变成无症状故障。
            log.error("参数 {} 的值不是整数: {},本次改用兜底值 {}", key, v, fallback);
            return fallback;
        }
    }

    @Override
    @Transactional(transactionManager = "opsTransactionManager", readOnly = true)
    public BigDecimal settingDecimal(String key, BigDecimal fallback) {
        String v = rawSetting(key);
        if (v == null) {
            return fallback;
        }
        try {
            return new BigDecimal(v.trim());
        } catch (NumberFormatException e) {
            log.error("参数 {} 的值不是数字: {},本次改用兜底值 {}", key, v, fallback);
            return fallback;
        }
    }

    private String rawSetting(String key) {
        java.util.Map<String, String> cache = settingCache;
        if (Instant.now().isAfter(cacheLoadedAt.plus(settingTtl))) {
            cache = settings.findAll().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                    PlatformSetting::getKey, PlatformSetting::getValue));
            settingCache = cache;
            cacheLoadedAt = Instant.now();
        }
        return cache.get(key);
    }

    @Override
    @Transactional(transactionManager = "opsTransactionManager", readOnly = true)
    public List<SettingView> allSettings() {
        return settings.findAllByOrderByCategoryAscKeyAsc().stream()
                .map(s -> new SettingView(s.getKey(), s.getValue(), s.getValueType().name(),
                        s.getCategory(), s.getLabel(), s.getDescription(), s.getUpdatedAt(), s.getUpdatedBy()))
                .toList();
    }

    @Override
    @Transactional("opsTransactionManager")
    public void updateSetting(String key, String value, String reason, long callerUserId) {
        requirePlatformOps(callerUserId);
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("必须填写改动理由");
        }
        PlatformSetting s = settings.findById(key)
                .orElseThrow(() -> new IllegalArgumentException("参数不存在: " + key));

        String normalized = validateAndNormalize(s, value);
        String old = s.getValue();
        if (normalized.equals(old)) {
            return;   // 没变就不留一条噪音记录
        }
        s.changeTo(normalized, callerUserId);
        settings.save(s);
        settingChanges.save(new PlatformSettingChange(key, old, normalized, callerUserId, reason.trim()));
        // 本实例立刻失效;别的实例等 TTL
        cacheLoadedAt = Instant.EPOCH;
        log.warn("平台参数变更: {} {} → {} 操作人={} 理由={}", key, old, normalized, callerUserId, reason);
    }

    /** 按声明的类型校验。存进去一个非法值,后果是热路径上每次读都退兜底——要在入口就挡住。 */
    private static String validateAndNormalize(PlatformSetting s, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("参数值不能为空");
        }
        String v = raw.trim();
        switch (s.getValueType()) {
            case INT -> {
                try { Long.parseLong(v); } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("「" + s.getLabel() + "」必须是整数");
                }
            }
            case DECIMAL -> {
                try { new BigDecimal(v); } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("「" + s.getLabel() + "」必须是数字");
                }
            }
            case BOOLEAN -> {
                if (!v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")) {
                    throw new IllegalArgumentException("「" + s.getLabel() + "」只能是 true 或 false");
                }
                v = v.toLowerCase();
            }
            case STRING -> { /* 不额外约束 */ }
        }
        return v;
    }

    @Override
    @Transactional(transactionManager = "opsTransactionManager", readOnly = true)
    public List<SettingChangeView> settingChanges(String key, long callerUserId) {
        requirePlatformOps(callerUserId);
        List<PlatformSettingChange> rows = (key == null || key.isBlank())
                ? settingChanges.findTop50ByOrderByChangedAtDesc()
                : settingChanges.findByKeyOrderByChangedAtDesc(key);
        return rows.stream().map(c -> new SettingChangeView(c.getId(), c.getKey(), c.getOldValue(),
                c.getNewValue(), c.getChangedBy(), c.getChangedAt(), c.getReason())).toList();
    }

    private void requirePlatformOps(long callerUserId) {
        if (!identityApi.hasRole(callerUserId, Role.PLATFORM_OPS)) {
            throw new IllegalStateException("需要平台运维角色");
        }
    }

    @Override
    @Transactional(transactionManager = "opsTransactionManager", readOnly = true)
    public java.util.List<RegionView> listRegions(String parentCode) {
        var rows = parentCode == null || parentCode.isBlank()
                ? regions.findByLevelOrderByCodeAsc((short) 1)
                : regions.findByParentCodeOrderByCodeAsc(parentCode.trim());
        return rows.stream()
                .map(r -> new RegionView(r.getCode(), r.getName(), r.getParentCode(), r.getLevel()))
                .toList();
    }
}
