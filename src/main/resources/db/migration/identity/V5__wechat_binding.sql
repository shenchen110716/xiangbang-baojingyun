-- 微信登录绑定。
--
-- 现有登录只有手机验证码。操作员用微信登录,需要把 openid 和账号对应起来。
--
-- **openid 单独一张表,不是往 user 上加一列。**
-- 一个人可能先用手机注册、后来才绑微信,也可能反过来;
-- 加列的话"没绑过"和"绑了空值"分不清,而且以后要支持多个微信主体
-- (公众号 openid 与小程序 openid 是不同的值)时要再改表结构。
CREATE TABLE identity.wechat_binding (
    open_id       VARCHAR(64) PRIMARY KEY,
    user_id       BIGINT      NOT NULL,
    -- unionid:同一主体下跨应用的稳定标识。可能为空(没开放 unionid 的场景)
    union_id      VARCHAR(64),
    nickname      VARCHAR(64),
    bound_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- **一个账号只能绑一个微信。**允许多个的话,"这个人是谁"就有多个答案,
-- 而操作员授权是按人给的 —— 一人多微信等于一份授权可以被多个微信身份使用
CREATE UNIQUE INDEX wechat_binding_user_idx ON identity.wechat_binding (user_id);

CREATE INDEX wechat_binding_union_idx ON identity.wechat_binding (union_id)
    WHERE union_id IS NOT NULL;

GRANT SELECT, INSERT, UPDATE, DELETE ON identity.wechat_binding TO identity_user;
