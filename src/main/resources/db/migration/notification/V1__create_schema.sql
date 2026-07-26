CREATE SCHEMA IF NOT EXISTS notification;

-- 统一出口(§4.2"通知 | IM/推送/模版消息统一出口 | 各域只发事件")。
-- 各域只管发事件,通知怎么送达是本域的事——不做的话每个域迟早各长一套通知代码。
CREATE TABLE notification.notification (
    id                BIGSERIAL PRIMARY KEY,
    recipient_user_id BIGINT       NOT NULL,
    type              VARCHAR(40)  NOT NULL,
    title             VARCHAR(100) NOT NULL,
    body              VARCHAR(500) NOT NULL,
    reference_id      BIGINT,
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    read_at           TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- 幂等:同一事件重复投递不应产生两条通知
    UNIQUE (recipient_user_id, type, reference_id)
);

GRANT USAGE ON SCHEMA notification TO notification_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA notification TO notification_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA notification TO notification_user;
