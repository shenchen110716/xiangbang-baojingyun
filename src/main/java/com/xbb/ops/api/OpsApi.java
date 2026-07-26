package com.xbb.ops.api;

import java.util.List;
import java.util.Optional;

/**
 * 运营域(§4.2)。当前只做**字典**——它是各域受控词表的运营侧抓手
 * (画像域的技能词表、评价标签目前都是硬编码,记在案的留白)。
 *
 * <p>RBAC 与审核入口暂不重复造:各域已有自己的鉴权(法人代表校验、JWT 身份),
 * 再叠一层通用 RBAC 需要先统一权限模型,是独立的一块。
 */
public interface OpsApi {

    record DictItemView(long id, String dictType, String key, String value, int sortOrder, boolean enabled) { }

    long addItem(String dictType, String key, String value, int sortOrder);

    void disableItem(long itemId);

    void enableItem(long itemId);

    /** 只返回启用的,按排序权重升序。 */
    List<DictItemView> itemsOf(String dictType);

    Optional<DictItemView> findItem(String dictType, String key);
}
