-- 岗位卡片要显示"哪家单位、在哪上班"。
--
-- 岗位域不能直接读组织域(铁律 3),只能靠 approved_org 这份副本 ——
-- 但它此前只存了 org_id 和法人,连名字都没有,于是求职端卡片上那两行只能是空白。
--
-- **两列都可空。**它们对应的事件字段是后加的,重放那之前落库的 outbox 载荷时
-- Jackson 给 null;NOT NULL 的话一次重放就把整条中继卡死。
ALTER TABLE job.approved_org
    ADD COLUMN name    VARCHAR(100),
    ADD COLUMN address VARCHAR(200);
