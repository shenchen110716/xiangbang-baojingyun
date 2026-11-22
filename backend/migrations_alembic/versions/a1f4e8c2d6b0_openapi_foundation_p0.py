"""open api platform P0 foundation (clients / request logs / rate counters)

Revision ID: a1f4e8c2d6b0
Revises: 9c3d1e5f7a2b
Create Date: 2026-11-19 00:00:00.000000

开放平台地基（story-p0-foundation AC1）：
- openapi_clients：对外 AppKey/AppSecret（Fernet 密文），scope/IP 白名单/
  启停/过期/prod|sandbox 环境。
- openapi_request_logs：每次 /openapi/v1 调用（含失败）一行，180 天保留。
- openapi_rate_counters：DB 固定窗口限流计数（多 worker 安全）。
- integration_nonces.seen_at 索引：清理任务按时间扫删的前提。

无布尔列；若后续追加布尔列，默认值必须用 sa.true()/sa.false()
（v4.2 Phase 2 的 text("1") 在 PostgreSQL 上翻车的教训）。
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'a1f4e8c2d6b0'
down_revision: Union[str, Sequence[str], None] = '9c3d1e5f7a2b'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.create_table(
        'openapi_clients',
        sa.Column('id', sa.Integer(), primary_key=True),
        sa.Column('enterprise_id', sa.Integer(), sa.ForeignKey('enterprises.id'), nullable=False),
        sa.Column('name', sa.String(length=64), nullable=False, server_default=''),
        sa.Column('key_id', sa.String(length=32), nullable=False),
        sa.Column('secret_encrypted', sa.Text(), nullable=False),
        sa.Column('secondary_secret_encrypted', sa.Text(), nullable=True),
        sa.Column('rotated_at', sa.DateTime(timezone=True), nullable=True),
        sa.Column('scopes', sa.Text(), nullable=False, server_default=''),
        sa.Column('allowed_employer_ids', sa.Text(), nullable=False, server_default=''),
        sa.Column('ip_allowlist', sa.Text(), nullable=False, server_default=''),
        sa.Column('status', sa.String(length=20), nullable=False, server_default='active'),
        sa.Column('expires_at', sa.DateTime(timezone=True), nullable=True),
        sa.Column('env', sa.String(length=10), nullable=False, server_default='prod'),
        # created_at 不允许 NULL：清理任务按 created_at < cutoff 扫删，
        # NULL 行会永远删不掉（review-p0 minor-2）。
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=False,
                  server_default=sa.func.now()),
        sa.UniqueConstraint('key_id', name='uq_openapi_clients_key_id'),
    )
    op.create_index('ix_openapi_clients_enterprise_id', 'openapi_clients', ['enterprise_id'])

    op.create_table(
        'openapi_request_logs',
        sa.Column('id', sa.Integer(), primary_key=True),
        sa.Column('request_id', sa.String(length=32), nullable=False),
        sa.Column('key_id', sa.String(length=32), nullable=True),
        sa.Column('enterprise_id', sa.Integer(), nullable=True),
        sa.Column('method', sa.String(length=10), nullable=False),
        sa.Column('path', sa.String(length=200), nullable=False),
        sa.Column('status', sa.Integer(), nullable=False),
        sa.Column('latency_ms', sa.Integer(), nullable=True),
        sa.Column('ip', sa.String(length=64), nullable=False, server_default=''),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=False,
                  server_default=sa.func.now()),
    )
    op.create_index('ix_openapi_request_logs_request_id', 'openapi_request_logs', ['request_id'])
    op.create_index('ix_openapi_request_logs_key_id', 'openapi_request_logs', ['key_id'])
    op.create_index('ix_openapi_request_logs_enterprise_id', 'openapi_request_logs', ['enterprise_id'])
    op.create_index('ix_openapi_request_logs_created_at', 'openapi_request_logs', ['created_at'])

    op.create_table(
        'openapi_rate_counters',
        sa.Column('id', sa.Integer(), primary_key=True),
        sa.Column('key_id', sa.String(length=32), nullable=False),
        sa.Column('bucket', sa.String(length=32), nullable=False, server_default='default'),
        sa.Column('window_start', sa.Integer(), nullable=False),
        sa.Column('count', sa.Integer(), nullable=False, server_default='0'),
    )
    op.create_index('ux_rate_counter_window', 'openapi_rate_counters',
                    ['key_id', 'bucket', 'window_start'], unique=True)

    op.create_index('ix_integration_nonces_seen_at', 'integration_nonces', ['seen_at'])


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_index('ix_integration_nonces_seen_at', table_name='integration_nonces')
    op.drop_index('ux_rate_counter_window', table_name='openapi_rate_counters')
    op.drop_table('openapi_rate_counters')
    op.drop_index('ix_openapi_request_logs_created_at', table_name='openapi_request_logs')
    op.drop_index('ix_openapi_request_logs_enterprise_id', table_name='openapi_request_logs')
    op.drop_index('ix_openapi_request_logs_key_id', table_name='openapi_request_logs')
    op.drop_index('ix_openapi_request_logs_request_id', table_name='openapi_request_logs')
    op.drop_table('openapi_request_logs')
    op.drop_index('ix_openapi_clients_enterprise_id', table_name='openapi_clients')
    op.drop_table('openapi_clients')
