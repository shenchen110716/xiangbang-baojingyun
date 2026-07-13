from datetime import datetime, timezone
from typing import Optional

from sqlalchemy import Boolean, DateTime, Integer, String
from sqlalchemy.orm import Mapped, mapped_column

from ..core.db import Base


class User(Base):
    __tablename__ = "users"
    id: Mapped[int] = mapped_column(primary_key=True)
    username: Mapped[str] = mapped_column(String(80), unique=True, index=True)
    password_hash: Mapped[str] = mapped_column(String(255))
    name: Mapped[str] = mapped_column(String(80), default="平台管理员")
    role: Mapped[str] = mapped_column(String(40), default="admin")
    enterprise_id: Mapped[Optional[int]] = mapped_column(Integer, nullable=True)
    phone: Mapped[str] = mapped_column(String(30), default="")
    status: Mapped[str] = mapped_column(String(30), default="active")
    active: Mapped[bool] = mapped_column(Boolean, default=True)
    is_owner: Mapped[bool] = mapped_column(Boolean, default=False)
    # SYSTEM-DESIGN-V4.md section 6.2: "密码修改、角色变更、账号停用后递增
    # session_version，旧 Token 立即失效". Embedded in the JWT at login
    # (core/security.py) and compared on every request; bumping this
    # invalidates every previously-issued token for the user immediately,
    # without needing a server-side token blocklist.
    session_version: Mapped[int] = mapped_column(Integer, default=1)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))
