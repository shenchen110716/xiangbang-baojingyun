-- 投影记下写入它的那个事件的发生时刻,用于忽略乱序到达的旧事件。
-- 没有它时,一条退避后重投的旧 ProfileUpdated 会把已经升级过的置信度覆盖回去,
-- 表现为"履约验证过的标签退回自述状态",无声无息。
ALTER TABLE matching.worker_projection ADD COLUMN source_event_at TIMESTAMPTZ;
