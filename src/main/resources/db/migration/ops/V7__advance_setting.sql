-- 借支额度上限。老系统那条"借支不超可用额度"要有个地方定这个额度。
--
-- 放参数中心而不是写死在代码里:各地工价差很多,写死的数字要么卡住高薪岗位、
-- 要么给低薪岗位批出还不起的钱,而改一个写死的数字要发一次版。
INSERT INTO ops.platform_setting (setting_key, value, value_type, category, label, description) VALUES
('advance.max.outstanding.cents', '300000', 'INT', 'ADVANCE', '单人未还借支上限(分)',
 '默认 3000 元。**连同已欠的一起算** —— 只看单笔的话,借十次小额就绕过了上限')
ON CONFLICT (setting_key) DO NOTHING;
