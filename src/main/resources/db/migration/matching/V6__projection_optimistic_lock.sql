-- worker_projection 有两个并发写入方(标签、信用分),都是"读整行—改一个字段—写回"。
-- 没有版本号时后提交者会整行盖掉先提交者:一次信用分更新就能把工人已验证的
-- 技能标签打回自述状态,无声无息。表现为反哺链路"时灵时不灵"。
ALTER TABLE matching.worker_projection ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- job_projection 同理:岗位事件与岗位画像事件两路并发写同一行。
ALTER TABLE matching.job_projection ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
