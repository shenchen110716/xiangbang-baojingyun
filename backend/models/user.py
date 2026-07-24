from datetime import datetime, timezone
from typing import Optional

from sqlalchemy import Boolean, CheckConstraint, DateTime, ForeignKey, Integer, String
from sqlalchemy.orm import Mapped, mapped_column

from ..core.db import Base


class User(Base):
    __tablename__ = "users"
    __table_args__ = (
        CheckConstraint(
            "enterprise_role IS NULL OR enterprise_role IN ('owner', 'project_manager')",
            name="ck_users_enterprise_role",
        ),
    )
    id: Mapped[int] = mapped_column(primary_key=True)
    username: Mapped[str] = mapped_column(String(80), unique=True, index=True)
    password_hash: Mapped[str] = mapped_column(String(255))
    name: Mapped[str] = mapped_column(String(80), default="平台管理员")
    role: Mapped[str] = mapped_column(String(40), default="admin")
    enterprise_id: Mapped[Optional[int]] = mapped_column(Integer, nullable=True)
    enterprise_role: Mapped[Optional[str]] = mapped_column(String(30), nullable=True)
    # 仅 role='insurer' 账号使用。和 salesperson（账号本身就是业务员实体）不同，
    # Insurer 已经是独立实体表，所以保司账号是"User 通过 insurer_id 关联到一个
    # 已存在的 Insurer"，不是"User 本身就是保司记录"。
    insurer_id: Mapped[Optional[int]] = mapped_column(ForeignKey("insurers.id"), nullable=True)
    phone: Mapped[str] = mapped_column(String(30), default="")
    status: Mapped[str] = mapped_column(String(30), default="active")
    active: Mapped[bool] = mapped_column(Boolean, default=True)
    is_owner: Mapped[bool] = mapped_column(Boolean, default=False)
    wx_openid: Mapped[Optional[str]] = mapped_column(String(64), unique=True, nullable=True)
    # SYSTEM-DESIGN-V4.md section 6.2: "密码修改、角色变更、账号停用后递增
    # session_version，旧 Token 立即失效". Embedded in the JWT at login
    # (core/security.py) and compared on every request; bumping this
    # invalidates every previously-issued token for the user immediately,
    # without needing a server-side token blocklist.
    session_version: Mapped[int] = mapped_column(Integer, default=1)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))
