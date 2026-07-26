-- 评价标签受控词表迁进运营字典(§5.3.1)。原本硬编码在 review.ReviewTag 里。
-- 与技能词表的区别:标签不只是个词,还带"正面/负面"和"严重度",所以用上了 attributes。
--
-- 方向做成两个 dict_type 而不是一个属性:两侧的词互不通用,分开之后
-- 前端取某一侧要显示的标签就是一次 itemsOf,不必取全量再过滤。
INSERT INTO ops.dictionary_item (dict_type, item_key, item_value, sort_order, attributes) VALUES
    ('REVIEW_TAG_ORG_RATES_WORKER', '准时到岗', '准时到岗', 1, '{"polarity":"POSITIVE"}'),
    ('REVIEW_TAG_ORG_RATES_WORKER', '手脚麻利', '手脚麻利', 2, '{"polarity":"POSITIVE"}'),
    ('REVIEW_TAG_ORG_RATES_WORKER', '服从管理', '服从管理', 3, '{"polarity":"POSITIVE"}'),
    ('REVIEW_TAG_ORG_RATES_WORKER', '干满工期', '干满工期', 4, '{"polarity":"POSITIVE"}'),
    ('REVIEW_TAG_ORG_RATES_WORKER', '态度好',   '态度好',   5, '{"polarity":"POSITIVE"}'),
    ('REVIEW_TAG_ORG_RATES_WORKER', '迟到早退', '迟到早退', 6, '{"polarity":"NEGATIVE","severity":"LIGHT"}'),
    ('REVIEW_TAG_ORG_RATES_WORKER', '消极怠工', '消极怠工', 7, '{"polarity":"NEGATIVE","severity":"MEDIUM"}'),
    ('REVIEW_TAG_ORG_RATES_WORKER', '不服管理', '不服管理', 8, '{"polarity":"NEGATIVE","severity":"MEDIUM"}'),
    ('REVIEW_TAG_ORG_RATES_WORKER', '技能不符', '技能不符', 9, '{"polarity":"NEGATIVE","severity":"MEDIUM"}'),
    ('REVIEW_TAG_ORG_RATES_WORKER', '中途跑单', '中途跑单', 10, '{"polarity":"NEGATIVE","severity":"HEAVY"}'),

    ('REVIEW_TAG_WORKER_RATES_ORG', '结算准时', '结算准时', 1, '{"polarity":"POSITIVE"}'),
    ('REVIEW_TAG_WORKER_RATES_ORG', '如实描述', '如实描述', 2, '{"polarity":"POSITIVE"}'),
    ('REVIEW_TAG_WORKER_RATES_ORG', '管理规范', '管理规范', 3, '{"polarity":"POSITIVE"}'),
    ('REVIEW_TAG_WORKER_RATES_ORG', '食宿达标', '食宿达标', 4, '{"polarity":"POSITIVE"}'),
    ('REVIEW_TAG_WORKER_RATES_ORG', '超时加班', '超时加班', 5, '{"polarity":"NEGATIVE","severity":"LIGHT"}'),
    ('REVIEW_TAG_WORKER_RATES_ORG', '管理粗暴', '管理粗暴', 6, '{"polarity":"NEGATIVE","severity":"MEDIUM"}'),
    ('REVIEW_TAG_WORKER_RATES_ORG', '食宿差',   '食宿差',   7, '{"polarity":"NEGATIVE","severity":"MEDIUM"}'),
    ('REVIEW_TAG_WORKER_RATES_ORG', '描述不实', '描述不实', 8, '{"polarity":"NEGATIVE","severity":"MEDIUM"}'),
    ('REVIEW_TAG_WORKER_RATES_ORG', '拖欠工资', '拖欠工资', 9, '{"polarity":"NEGATIVE","severity":"HEAVY"}');

-- 严重度权重单独一张词表,而不是把 0.5/1.0/2.5 直接写进每个标签的属性里:
-- 这样"整体调校三档权重"和"把某个标签重新定档"是两件独立的事,各自只改一个地方。
INSERT INTO ops.dictionary_item (dict_type, item_key, item_value, sort_order) VALUES
    ('REVIEW_SEVERITY_WEIGHT', 'LIGHT',  '0.5', 1),
    ('REVIEW_SEVERITY_WEIGHT', 'MEDIUM', '1.0', 2),
    ('REVIEW_SEVERITY_WEIGHT', 'HEAVY',  '2.5', 3);
