"""open api platform P3 compliance: insured id_number hash/cipher dual-write

Revision ID: e5f8a1c3d7b2
Revises: d8e3f6a2b9c4
Create Date: 2026-11-22 00:00:00.000000

P3 合规硬化（story-p3-compliance AC1）：
- insured_people 加 id_number_hash（String(64)，NOT NULL server_default=''，
  带索引，用于查重/等值检索）与 id_number_cipher（Text 可空，Fernet 密文，
  用于展示/导出解密）。
- 迁移内完成存量回填：分批（500/批）、幂等可重跑（只挑 hash/cipher 缺失
  且明文非空的行），逻辑在 backend/core/migrations.backfill_insured_id_numbers，
  与 SQLite 启动桥共用同一份实现。
- 明文列 id_number 保留不动：双写期回滚通道，DROP 属 story-p3b。
无布尔列；不改 created_at。

运维注意（review-p3 B2）：在非 SQLite 库上执行本迁移**必须**先设置正式
`ID_ENCRYPTION_KEY` 环境变量——回填入口有守卫，检测到开发缺省密钥会直接
RuntimeError 拒绝执行（错钥写入的 hash/cipher 无法通过重跑自愈）。生产
PG16 模板库验证同样要带生产密钥跑。
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'e5f8a1c3d7b2'
down_revision: Union[str, Sequence[str], None] = 'd8e3f6a2b9c4'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.add_column('insured_people',
                  sa.Column('id_number_hash', sa.String(length=64),
                            nullable=False, server_default=''))
    op.add_column('insured_people',
                  sa.Column('id_number_cipher', sa.Text(), nullable=True))
    op.create_index('ix_insured_people_id_number_hash', 'insured_people',
                    ['id_number_hash'])

    # 存量回填（幂等、分批；与 SQLite 桥共用实现，见模块 docstring）。
    from backend.core.migrations import backfill_insured_id_numbers
    backfill_insured_id_numbers(op.get_bind())


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_index('ix_insured_people_id_number_hash', table_name='insured_people')
    op.drop_column('insured_people', 'id_number_cipher')
    op.drop_column('insured_people', 'id_number_hash')
