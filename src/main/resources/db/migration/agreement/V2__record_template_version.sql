-- 协议要记下它是用**哪一版模板**生成的(§6.2)。
-- 模板以后还会改,纠纷举证时得能翻出签署当时的那一版文本;只存正文不够——
-- 正文能证明"签的是这些字",版本号能证明"这些字是当时的标准文本"。
ALTER TABLE agreement.agreement ADD COLUMN template_key     VARCHAR(50);
ALTER TABLE agreement.agreement ADD COLUMN template_version INT;
