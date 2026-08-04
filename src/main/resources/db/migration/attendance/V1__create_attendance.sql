CREATE SCHEMA IF NOT EXISTS attendance;

-- 考勤:某人在某个履约单下、某一天的出勤。
--
-- 为什么单独成域而不是塞进结算域:结算域的定位是"纯计算可回放",
-- 而考勤是**可变的采集数据**(打卡、导入、人工订正、确认)。
-- 更实际的理由是数据量 —— 一人一天一行,量级远超其它任何一张表,
-- 混进结算 schema 会让那个 schema 的增长变得不可预测。
--
-- 对应老系统的 AppUserWorkday。字段做了收敛:
-- 老系统有 jobId / jobSnapshotId / jobRegistrationId / factoryId / factoryJobId 五个外键,
-- 其中后四个都能从履约单推出来。冗余外键在导入时最容易填错,而填错了对不上账。

CREATE TABLE attendance.workday (
    id             BIGSERIAL PRIMARY KEY,
    -- 履约单(老系统的 jobRegistrationId)。考勤永远挂在履约单上,
    -- 因为"这个人这天为哪个岗位干活"只有履约单说得清。
    application_id BIGINT       NOT NULL,
    job_id         BIGINT       NOT NULL,
    worker_user_id BIGINT       NOT NULL,
    work_date      DATE         NOT NULL,

    begin_at       TIMESTAMPTZ,
    end_at         TIMESTAMPTZ,
    -- 工时。可由起止推算,也可以直接导入(有些工厂只给汇总工时,没有打卡时间)。
    -- 用 NUMERIC 不用浮点:工时会参与工资计算。
    minutes        INT          NOT NULL DEFAULT 0,

    -- PUNCH 打卡 / IMPORT 导入 / MANUAL 人工录入。
    -- 记来源是为了出账疑问时能回答"这条是怎么来的"。
    source         VARCHAR(16)  NOT NULL,
    -- DRAFT 待确认 / CONFIRMED 已确认。已确认的才进入计薪。
    status         VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',

    remark         VARCHAR(200),
    confirmed_at   TIMESTAMPTZ,
    confirmed_by   BIGINT,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version        BIGINT       NOT NULL DEFAULT 0,

    -- **一个履约单一天只有一条。** 这是防重复计薪的根:
    -- 少了它,导入两次就是两份工时,工资直接翻倍,而账面上看不出异常
    -- (两条记录各自都是合法的)。
    CONSTRAINT workday_unique_day UNIQUE (application_id, work_date),
    CONSTRAINT workday_source_ck  CHECK (source IN ('PUNCH', 'IMPORT', 'MANUAL')),
    CONSTRAINT workday_status_ck  CHECK (status IN ('DRAFT', 'CONFIRMED')),
    CONSTRAINT workday_minutes_ck CHECK (minutes >= 0 AND minutes <= 1440),
    -- 有起止时间就必须成对,且顺序正确
    CONSTRAINT workday_time_ck    CHECK (
        (begin_at IS NULL AND end_at IS NULL) OR
        (begin_at IS NOT NULL AND end_at IS NOT NULL AND end_at >= begin_at))
);

CREATE INDEX workday_worker_idx ON attendance.workday (worker_user_id, work_date DESC);
CREATE INDEX workday_job_idx    ON attendance.workday (job_id, work_date DESC);
CREATE INDEX workday_status_idx ON attendance.workday (status) WHERE status = 'DRAFT';

-- 变更留痕。考勤直接决定发多少钱,"谁把这天的工时从 8 小时改成 12 小时"
-- 事后必须查得到。老系统靠 isRecount 一个布尔位表示"需要重算",
-- 既说不出改了什么,也说不出是谁改的。
CREATE TABLE attendance.workday_change (
    id           BIGSERIAL PRIMARY KEY,
    workday_id   BIGINT       NOT NULL,
    field        VARCHAR(32)  NOT NULL,
    old_value    VARCHAR(64),
    new_value    VARCHAR(64),
    changed_by   BIGINT,
    changed_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    reason       VARCHAR(200)
);
CREATE INDEX workday_change_idx ON attendance.workday_change (workday_id, changed_at DESC);

-- 履约单的只读副本。铁律 3:需要他域数据时订阅事件、在本域维护副本,
-- 不跨域查库(铁律 1 也会在数据库层挡住)。
CREATE TABLE attendance.engaged_worker (
    application_id BIGINT PRIMARY KEY,
    job_id         BIGINT NOT NULL,
    worker_user_id BIGINT NOT NULL,
    -- 录考勤要校验"调用者是不是这个岗位所属组织的法人代表",所以副本里要带上组织
    org_id         BIGINT NOT NULL,
    accepted_at    TIMESTAMPTZ NOT NULL
);
CREATE INDEX engaged_worker_org_idx ON attendance.engaged_worker (org_id);

-- 已通过审核的组织(只读副本)。用来回答"谁是这个组织的法人代表"。
CREATE TABLE attendance.approved_org (
    org_id            BIGINT PRIMARY KEY,
    legal_rep_user_id BIGINT NOT NULL,
    approved_at       TIMESTAMPTZ NOT NULL
);

GRANT USAGE ON SCHEMA attendance TO attendance_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA attendance TO attendance_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA attendance TO attendance_user;
