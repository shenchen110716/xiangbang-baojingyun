from datetime import datetime

from sqlalchemy import DateTime, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from ..core.db import Base


class SystemSetting(Base):
    """平台端运营配置的 key-value 存储（存储/短信/OCR 等）。

    敏感项（is_secret，由 services/settings 注册表判定）在写入前用项目现有的
    Fernet（ID_ENCRYPTION_KEY 派生）加密，value 里存的是密文；非敏感项存明文。
    加密主密钥本身只在环境变量，绝不入库。读取优先级 DB → 环境变量 → 默认值。"""

    __tablename__ = "system_settings"

    key: Mapped[str] = mapped_column(String(64), primary_key=True)
    value: Mapped[str] = mapped_column(Text, default="")
    updated_by: Mapped[int | None] = mapped_column(Integer, nullable=True)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)
