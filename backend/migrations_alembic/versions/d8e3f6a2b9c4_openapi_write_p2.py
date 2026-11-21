"""open api platform P2 write: declarations / webhooks / idempotency

Revision ID: d8e3f6a2b9c4
Revises: c7d2e9f4a1b8
Create Date: 2026-11-22 00:00:00.000000

P2 写入面（story-p2-write AC1）：
- openapi_declarations / openapi_declaration_items：批量参停保申报单与
  逐人明细（异步受理回执，declaration_id 可查进度）。
- openapi_webhook_endpoints：企业注册的回调地址（key_id 记录投递签名密钥）。
- openapi_webhook_deliveries：事件 outbox（与业务写同事务落库，后台循环
  投递，退避 1m/5m/30m/2h/12h）。endpoint_id 故意不设 FK——端点删除后
  历史投递行保留为审计痕迹。
- openapi_idempotency_keys：写接口幂等键，(key_id, idem_key) 唯一。
- enterprises 加可空 usage_alert_threshold（balance.low 预警阈值，AC15）。

无布尔列；created_at NOT NULL + server_default=sa.func.now()（NULL 行会
永远躲过按时间清理，review-p0 minor-2 的教训）。
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'd8e3f6a2b9c4'
down_revision: Union[str, Sequence[str], None] = 'c7d2e9f4a1b8'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.create_table(
        'openapi_declarations',
        sa.Column('id', sa.Integer(), primary_key=True),
        sa.Column('enterprise_id', sa.Integer(), sa.ForeignKey('enterprises.id'), nullable=False),
        sa.Column('key_id', sa.String(length=32), nullable=False, server_default=''),
        sa.Column('kind', sa.String(length=20), nullable=False),
        sa.Column('status', sa.String(length=20), nullable=False, server_default='accepted'),
        sa.Column('item_count', sa.Integer(), nullable=False, server_default='0'),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=False,
                  server_default=sa.func.now()),
        sa.Column('finished_at', sa.DateTime(timezone=True), nullable=True),
    )
    op.create_index('ix_openapi_declarations_enterprise_id',
                    'openapi_declarations', ['enterprise_id'])
    op.create_index('ix_openapi_declarations_status',
                    'openapi_declarations', ['status'])

    op.create_table(
        'openapi_declaration_items',
        sa.Column('id', sa.Integer(), primary_key=True),
        sa.Column('declaration_id', sa.Integer(),
                  sa.ForeignKey('openapi_declarations.id'), nullable=False),
        sa.Column('enterprise_id', sa.Integer(), sa.ForeignKey('enterprises.id'), nullable=False),
        sa.Column('person_id', sa.Integer(), nullable=True),
        sa.Column('external_person_id', sa.String(length=64), nullable=False, server_default=''),
        sa.Column('name', sa.String(length=80), nullable=False, server_default=''),
        sa.Column('id_number', sa.String(length=40), nullable=False, server_default=''),
        sa.Column('status', sa.String(length=20), nullable=False, server_default='pending'),
        sa.Column('reason', sa.Text(), nullable=False, server_default=''),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=False,
                  server_default=sa.func.now()),
        sa.Column('finished_at', sa.DateTime(timezone=True), nullable=True),
    )
    op.create_index('ix_openapi_declaration_items_declaration_id',
                    'openapi_declaration_items', ['declaration_id'])
    op.create_index('ix_openapi_declaration_items_enterprise_id',
                    'openapi_declaration_items', ['enterprise_id'])
    # insured_status 确认钩子按 (person_id, status='pending') 找待翻转项。
    op.create_index('ix_openapi_declaration_items_person_status',
                    'openapi_declaration_items', ['person_id', 'status'])

    op.create_table(
        'openapi_webhook_endpoints',
        sa.Column('id', sa.Integer(), primary_key=True),
        sa.Column('enterprise_id', sa.Integer(), sa.ForeignKey('enterprises.id'), nullable=False),
        sa.Column('url', sa.Text(), nullable=False),
        sa.Column('key_id', sa.String(length=32), nullable=False, server_default=''),
        sa.Column('status', sa.String(length=20), nullable=False, server_default='active'),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=False,
                  server_default=sa.func.now()),
    )
    op.create_index('ix_openapi_webhook_endpoints_enterprise_id',
                    'openapi_webhook_endpoints', ['enterprise_id'])

    op.create_table(
        'openapi_webhook_deliveries',
        sa.Column('id', sa.Integer(), primary_key=True),
        sa.Column('enterprise_id', sa.Integer(), sa.ForeignKey('enterprises.id'), nullable=False),
        # 无 FK（见模块 docstring）。
        sa.Column('endpoint_id', sa.Integer(), nullable=False),
        sa.Column('event_id', sa.String(length=32), nullable=False),
        sa.Column('event_type', sa.String(length=40), nullable=False),
        sa.Column('payload', sa.Text(), nullable=False, server_default=''),
        sa.Column('status', sa.String(length=20), nullable=False, server_default='pending'),
        sa.Column('attempts', sa.Integer(), nullable=False, server_default='0'),
        sa.Column('next_retry_at', sa.DateTime(timezone=True), nullable=True),
        sa.Column('last_error', sa.Text(), nullable=False, server_default=''),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=False,
                  server_default=sa.func.now()),
        sa.Column('delivered_at', sa.DateTime(timezone=True), nullable=True),
    )
    op.create_index('ix_openapi_webhook_deliveries_enterprise_id',
                    'openapi_webhook_deliveries', ['enterprise_id'])
    op.create_index('ix_openapi_webhook_deliveries_endpoint_id',
                    'openapi_webhook_deliveries', ['endpoint_id'])
    op.create_index('ix_openapi_webhook_deliveries_event_id',
                    'openapi_webhook_deliveries', ['event_id'])
    # 投递循环按 status='pending' 轮询取活。
    op.create_index('ix_openapi_webhook_deliveries_status',
                    'openapi_webhook_deliveries', ['status'])

    op.create_table(
        'openapi_idempotency_keys',
        sa.Column('id', sa.Integer(), primary_key=True),
        sa.Column('key_id', sa.String(length=32), nullable=False),
        sa.Column('idem_key', sa.String(length=128), nullable=False),
        sa.Column('request_hash', sa.String(length=64), nullable=False, server_default=''),
        sa.Column('response_snapshot', sa.Text(), nullable=False, server_default=''),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=False,
                  server_default=sa.func.now()),
    )
    op.create_index('ux_openapi_idempotency_key', 'openapi_idempotency_keys',
                    ['key_id', 'idem_key'], unique=True)

    op.add_column('enterprises',
                  sa.Column('usage_alert_threshold', sa.Float(), nullable=True))


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column('enterprises', 'usage_alert_threshold')
    op.drop_index('ux_openapi_idempotency_key', table_name='openapi_idempotency_keys')
    op.drop_table('openapi_idempotency_keys')
    op.drop_index('ix_openapi_webhook_deliveries_status',
                  table_name='openapi_webhook_deliveries')
    op.drop_index('ix_openapi_webhook_deliveries_event_id',
                  table_name='openapi_webhook_deliveries')
    op.drop_index('ix_openapi_webhook_deliveries_endpoint_id',
                  table_name='openapi_webhook_deliveries')
    op.drop_index('ix_openapi_webhook_deliveries_enterprise_id',
                  table_name='openapi_webhook_deliveries')
    op.drop_table('openapi_webhook_deliveries')
    op.drop_index('ix_openapi_webhook_endpoints_enterprise_id',
                  table_name='openapi_webhook_endpoints')
    op.drop_table('openapi_webhook_endpoints')
    op.drop_index('ix_openapi_declaration_items_person_status',
                  table_name='openapi_declaration_items')
    op.drop_index('ix_openapi_declaration_items_enterprise_id',
                  table_name='openapi_declaration_items')
    op.drop_index('ix_openapi_declaration_items_declaration_id',
                  table_name='openapi_declaration_items')
    op.drop_table('openapi_declaration_items')
    op.drop_index('ix_openapi_declarations_status', table_name='openapi_declarations')
    op.drop_index('ix_openapi_declarations_enterprise_id',
                  table_name='openapi_declarations')
    op.drop_table('openapi_declarations')
