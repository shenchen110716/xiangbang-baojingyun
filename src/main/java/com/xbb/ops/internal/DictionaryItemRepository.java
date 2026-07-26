package com.xbb.ops.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface DictionaryItemRepository extends JpaRepository<DictionaryItem, Long> {

    List<DictionaryItem> findByDictTypeAndEnabledTrueOrderBySortOrderAsc(String dictType);

    Optional<DictionaryItem> findByDictTypeAndKey(String dictType, String key);
}
