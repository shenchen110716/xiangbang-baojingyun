-- 服务站改为平台统一设立:先建实体,再关联站长(可改)。
--
-- 改动前:服务站和工厂、企业一样,由某个用户提交入驻、平台审核通过,
-- 提交人自动成为法人代表且**此后不可更改**。
--
-- 改动后:服务站由平台直接建立,建出来时**还没有站长**;站长由平台单独指派,
-- 之后可以换人。工厂和企业不受影响,仍走"用户提交 → 平台审核"。
--
-- **为什么服务站要特殊。**它是平台自己的经营网点,不是入驻商户:
-- 平台先规划好"郑州高新区服务站"这个点位,再决定派谁去管。
-- 而按原来的模型,得先找到一个人、让他去提交申请,点位才存在 ——
-- 顺序反了,而且换站长时没有任何办法。

-- 法人代表改为可空:服务站刚建出来时还没指派站长。
-- **工厂和企业仍然必须有法人代表**,由下面那条 CHECK 保证
ALTER TABLE org.organization ALTER COLUMN legal_rep_user_id DROP NOT NULL;

ALTER TABLE org.organization
    ADD CONSTRAINT organization_legal_rep_required_ck CHECK (
        type = 'SERVICE_STATION' OR legal_rep_user_id IS NOT NULL);

-- 站长变更留痕。老系统 M10 §4.3 那条"先留痕后变更"值得照搬 ——
-- 换站长会改变谁能设分成比例、谁能签联合协议,事后必须查得到是谁在什么时候换的。
CREATE TABLE org.station_master_change (
    id              BIGSERIAL PRIMARY KEY,
    org_id          BIGINT      NOT NULL,
    old_user_id     BIGINT,
    new_user_id     BIGINT,
    changed_by      BIGINT      NOT NULL,
    reason          VARCHAR(200) NOT NULL,
    changed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- 换成一样的人不是变更,是误操作。让它落库只会在审计时制造噪音
    CONSTRAINT station_master_change_differs CHECK (old_user_id IS DISTINCT FROM new_user_id)
);

CREATE INDEX station_master_change_org_idx ON org.station_master_change (org_id, id DESC);

-- 铁律 1:新表要显式授权
GRANT SELECT, INSERT ON org.station_master_change TO org_user;
GRANT USAGE, SELECT ON SEQUENCE org.station_master_change_id_seq TO org_user;
