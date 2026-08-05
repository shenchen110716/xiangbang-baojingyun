-- 业务员自动升级的两个参数。
INSERT INTO ops.platform_setting (setting_key, value, value_type, category, label, description) VALUES
('broker.upgrade.deal.threshold', '1', 'INT', 'BROKER', '自动升级所需成交单数',
 '分享出去后凑满几单成交,分享人自动升级为业务员。**填 0 表示对方报名即升级**(不等成交)。岗位与商品合并计数'),
('broker.default.station.org.id', '0', 'INT', 'BROKER', '平台默认服务站(组织编号)',
 '自动升级的业务员若继承不到服务站,归到这个站。填 0 表示暂不归站,由平台事后指派')
ON CONFLICT (setting_key) DO NOTHING;
