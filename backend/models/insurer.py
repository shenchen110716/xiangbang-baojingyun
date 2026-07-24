from datetime import datetime, timezone
from typing import Optional

from sqlalchemy import DateTime, String
from sqlalchemy.orm import Mapped, mapped_column

from ..core.db import Base


class Insurer(Base):
    # 7-24 保司工作台设计翻转了 7-15《保司分账户充值与审核》"不引入独立 Insurer
    # 实体表"的决定：字符串关联做不到可靠的数据隔离，而这正是保司账号登录后
    # "看不到别的保司数据"这条安全边界的前提。name/contact/phone 是当前生效值；
    # pending_* 是保司提交、平台审核通过前的暂存值，两段式生效见
    # routers/insurers.py 的 review_insurer_edit。
    __tablename__ = "insurers"
    id: Mapped[int] = mapped_column(primary_key=True)
    name: Mapped[str] = mapped_column(String(100))
    contact: Mapped[str] = mapped_column(String(80), default="")
    phone: Mapped[str] = mapped_column(String(30), default="")
    credit_code: Mapped[str] = mapped_column(String(40), default="")
    email: Mapped[str] = mapped_column(String(160), default="")
    address: Mapped[str] = mapped_column(String(200), default="")
    status: Mapped[str] = mapped_column(String(20), default="active")
    pending_name: Mapped[Optional[str]] = mapped_column(String(100), nullable=True)
    pending_contact: Mapped[Optional[str]] = mapped_column(String(80), nullable=True)
    pending_phone: Mapped[Optional[str]] = mapped_column(String(30), nullable=True)
    pending_credit_code: Mapped[Optional[str]] = mapped_column(String(40), nullable=True)
    pending_email: Mapped[Optional[str]] = mapped_column(String(160), nullable=True)
    pending_address: Mapped[Optional[str]] = mapped_column(String(200), nullable=True)
    pending_submitted_at: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))
