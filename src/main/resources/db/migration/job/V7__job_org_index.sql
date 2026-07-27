-- findByOrgId 用于薪资异常检测(语音发岗必经)。job.job 此前没有任何非主键索引。
CREATE INDEX ix_job_org ON job.job (org_id);
