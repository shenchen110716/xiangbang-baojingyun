-- 技能标签受控词表迁进运营字典(§5.2.1"标签树由**平台运营维护**,不由 LLM 扩张")。
-- 这些词原本硬编码在 profile.ProfileTag 里,Plan6/8 都记过"等运营字典这个抓手";
-- 运营域建好后接上来,运营终于能自己增删词条而不用改代码发版。
INSERT INTO ops.dictionary_item (dict_type, item_key, item_value, sort_order) VALUES
    ('SKILL_TAG', '普工', '普工', 1),
    ('SKILL_TAG', '质检', '质检', 2),
    ('SKILL_TAG', '叉车', '叉车', 3),
    ('SKILL_TAG', '电工', '电工', 4),
    ('SKILL_TAG', '贴片', '贴片', 5),
    ('SKILL_TAG', '分拣', '分拣', 6),
    ('SKILL_TAG', '打包', '打包', 7),
    ('SKILL_TAG', '理货', '理货', 8),
    ('SKILL_TAG', '焊工', '焊工', 9),
    ('SKILL_TAG', '注塑', '注塑', 10),
    ('SKILL_TAG', '包装', '包装', 11),
    ('SKILL_TAG', '搬运', '搬运', 12),
    ('SKILL_TAG', '仓管', '仓管', 13),
    ('SKILL_TAG', '客服', '客服', 14),
    ('SKILL_TAG', '文员', '文员', 15),
    ('SKILL_TAG', '保安', '保安', 16);
