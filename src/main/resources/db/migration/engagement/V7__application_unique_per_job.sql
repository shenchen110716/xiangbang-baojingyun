-- 同一个人对同一个岗位只能有一条报名记录。
--
-- 缺了这条约束的后果不是"多几行脏数据":同一工人可以重复报名同一岗位并被重复录用,
-- 独占全部名额,让真实求职者一个都进不来;而且下游 5 个唯一约束
-- (agreement/settlement/payout/commission 都建在 application_id 或 settlement_id 上)
-- **全部绕过**,因为三次报名的 applicationId 各不相同 —— 于是三份协议、三条结算、
-- 三笔工资、三份佣金。整条资金链的防线都建在下游,唯独漏了最上游这一条。
--
-- 存量数据里若已有重复,这条会失败;那正是需要人先处理的信号,不该静默跳过。
ALTER TABLE engagement.application
    ADD CONSTRAINT uq_application_job_applicant UNIQUE (job_id, applicant_user_id);
