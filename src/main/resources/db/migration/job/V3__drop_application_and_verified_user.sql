-- 报名/录用状态机(application)与实名副本(verified_user)搬到 engagement 域(履约域拆分,Plan7)。
DROP TABLE job.application;
DROP TABLE job.verified_user;
