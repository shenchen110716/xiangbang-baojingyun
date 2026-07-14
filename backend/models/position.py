from datetime import datetime, timezone
from typing import Optional

from sqlalchemy import DateTime, ForeignKey, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from ..core.db import Base


class WorkPosition(Base):
    __tablename__ = "work_positions"
    id: Mapped[int] = mapped_column(primary_key=True)
    enterprise_id: Mapped[int] = mapped_column(ForeignKey("enterprises.id"))
    actual_employer_id: Mapped[Optional[int]] = mapped_column(ForeignKey("actual_employers.id"), nullable=True)
    actual_employer: Mapped[str] = mapped_column(String(160), default="")
    name: Mapped[str] = mapped_column(String(100))
    occupation_class: Mapped[str] = mapped_column(String(30), default="待定")
    plan_id: Mapped[Optional[int]] = mapped_column(ForeignKey("insurance_plans.id"), nullable=True)
    status: Mapped[str] = mapped_column(String(30), default="pending")
    created_by: Mapped[Optional[int]] = mapped_column(ForeignKey("users.id"), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))


class PositionVideo(Base):
    __tablename__ = "position_videos"
    id: Mapped[int] = mapped_column(primary_key=True)
    position_id: Mapped[int] = mapped_column(ForeignKey("work_positions.id"))
    name: Mapped[str] = mapped_column(String(160))
    url: Mapped[str] = mapped_column(Text, default="")
    status: Mapped[str] = mapped_column(String(30), default="pending")
    review_note: Mapped[str] = mapped_column(Text, default="")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))
