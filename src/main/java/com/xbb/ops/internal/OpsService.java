package com.xbb.ops.internal;

import com.xbb.ops.api.OpsApi;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
class OpsService implements OpsApi {

    private final DictionaryItemRepository items;

    OpsService(DictionaryItemRepository items) {
        this.items = items;
    }

    @Override
    @Transactional("opsTransactionManager")
    public long addItem(String dictType, String key, String value, int sortOrder) {
        return items.save(new DictionaryItem(dictType, key, value, sortOrder)).getId();
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
        DictionaryItem item = items.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("字典项不存在"));
        item.setEnabled(enabled);
        items.save(item);
    }

    @Override
    @Transactional(transactionManager = "opsTransactionManager", readOnly = true)
    public List<DictItemView> itemsOf(String dictType) {
        return items.findByDictTypeAndEnabledTrueOrderBySortOrderAsc(dictType).stream()
                .map(OpsService::toView).toList();
    }

    @Override
    @Transactional(transactionManager = "opsTransactionManager", readOnly = true)
    public Optional<DictItemView> findItem(String dictType, String key) {
        return items.findByDictTypeAndKey(dictType, key).map(OpsService::toView);
    }

    private static DictItemView toView(DictionaryItem i) {
        return new DictItemView(i.getId(), i.getDictType(), i.getKey(), i.getValue(),
                i.getSortOrder(), i.isEnabled());
    }
}
