CREATE SCHEMA IF NOT EXISTS collab;

-- §6.5.1 工作任务:内部管理工具,不是 C 端功能。
-- 可关联岗位/用工单位,"让'招工进度'这类协同事有抓手"。
CREATE TABLE collab.work_task (
    id            BIGSERIAL PRIMARY KEY,
    title         VARCHAR(200) NOT NULL,
    detail        TEXT,
    creator_user_id BIGINT     NOT NULL,
    assignee_user_id BIGINT    NOT NULL,
    related_job_id  BIGINT,
    related_org_id  BIGINT,
    progress      INT          NOT NULL DEFAULT 0,
    status        VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    closed_at     TIMESTAMPTZ
);

GRANT USAGE ON SCHEMA collab TO collab_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA collab TO collab_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA collab TO collab_user;
