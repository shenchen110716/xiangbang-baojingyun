"""open api platform P1 read-only: export_tasks

Revision ID: c7d2e9f4a1b8
Revises: a1f4e8c2d6b0
Create Date: 2026-11-21 00:00:00.000000

P1 只读面（story-p1-readonly AC1）：异步导出任务表 export_tasks。
- params_json 存过滤条件与脱敏口径快照（入队时按 key 的 scope 固化，
  后台执行时不回查 scope——导出内容与入队那一刻的权限一致）。
- status: pending | processing | done | failed。
- file_url 是 core/storage.save_bytes 返回的标记（/uploads/… 或 s3://…），
  对外永远只经短时签名 URL 下发，绝不直接暴露。

无布尔列；created_at NOT NULL + server_default=sa.func.now()（NULL 行会
永远躲过按时间清理，review-p0 minor-2 的教训）。
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'c7d2e9f4a1b8'
down_revision: Union[str, Sequence[str], None] = 'a1f4e8c2d6b0'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.create_table(
        'export_tasks',
        sa.Column('id', sa.Integer(), primary_key=True),
        sa.Column('enterprise_id', sa.Integer(), sa.ForeignKey('enterprises.id'), nullable=False),
        sa.Column('key_id', sa.String(length=32), nullable=False, server_default=''),
        sa.Column('task_type', sa.String(length=40), nullable=False, server_default='insured_persons'),
        sa.Column('params_json', sa.Text(), nullable=False, server_default=''),
        sa.Column('status', sa.String(length=20), nullable=False, server_default='pending'),
        sa.Column('file_url', sa.Text(), nullable=False, server_default=''),
        sa.Column('error', sa.Text(), nullable=False, server_default=''),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=False,
                  server_default=sa.func.now()),
        sa.Column('finished_at', sa.DateTime(timezone=True), nullable=True),
    )
    op.create_index('ix_export_tasks_enterprise_id', 'export_tasks', ['enterprise_id'])
    # 后台任务循环按 status='pending' 轮询取活。
    op.create_index('ix_export_tasks_status', 'export_tasks', ['status'])


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_index('ix_export_tasks_status', table_name='export_tasks')
    op.drop_index('ix_export_tasks_enterprise_id', table_name='export_tasks')
    op.drop_table('export_tasks')
