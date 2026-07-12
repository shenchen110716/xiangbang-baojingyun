from datetime import datetime, timezone

from sqlalchemy import DateTime, Float, ForeignKey, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from ..core.db import Base


class Claim(Base):
    __tablename__ = "claims"
    id: Mapped[int] = mapped_column(primary_key=True)
    enterprise_id: Mapped[int] = mapped_column(ForeignKey("enterprises.id"))
    person_id: Mapped[int] = mapped_column(ForeignKey("insured_people.id"))
    claim_no: Mapped[str] = mapped_column(String(80), unique=True)
    description: Mapped[str] = mapped_column(Text, default="")
    status: Mapped[str] = mapped_column(String(30), default="reported")
    amount: Mapped[float] = mapped_column(Float, default=0)
    accident_at: Mapped[str] = mapped_column(String(30), default="")
    accident_place: Mapped[str] = mapped_column(String(200), default="")
    accident_type: Mapped[str] = mapped_column(String(60), default="工伤事故")
    hospital: Mapped[str] = mapped_column(String(160), default="")
    diagnosis: Mapped[str] = mapped_column(Text, default="")
    medical_cost: Mapped[float] = mapped_column(Float, default=0)
    contact_name: Mapped[str] = mapped_column(String(80), default="")
    contact_phone: Mapped[str] = mapped_column(String(30), default="")
    insurer_report_no: Mapped[str] = mapped_column(String(100), default="")
    current_handler: Mapped[str] = mapped_column(String(80), default="平台理赔专员")
    deadline: Mapped[str] = mapped_column(String(30), default="")
    approved_amount: Mapped[float] = mapped_column(Float, default=0)
    paid_at: Mapped[str] = mapped_column(String(30), default="")
    rejection_reason: Mapped[str] = mapped_column(Text, default="")
    review_note: Mapped[str] = mapped_column(Text, default="")
    sla_deadline: Mapped[str] = mapped_column(String(30), default="")
    risk_level: Mapped[str] = mapped_column(String(20), default="normal")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))


class ClaimTimeline(Base):
    __tablename__ = "claim_timelines"
    id: Mapped[int] = mapped_column(primary_key=True)
    claim_id: Mapped[int] = mapped_column(ForeignKey("claims.id"))
    node: Mapped[str] = mapped_column(String(40))
    action: Mapped[str] = mapped_column(String(100))
    note: Mapped[str] = mapped_column(Text, default="")
    operator: Mapped[str] = mapped_column(String(80), default="系统")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))


class ClaimDocument(Base):
    __tablename__ = "claim_documents"
    id: Mapped[int] = mapped_column(primary_key=True)
    claim_id: Mapped[int] = mapped_column(ForeignKey("claims.id"))
    name: Mapped[str] = mapped_column(String(160))
    url: Mapped[str] = mapped_column(Text, default="")
    doc_type: Mapped[str] = mapped_column(String(40), default="other")
    status: Mapped[str] = mapped_column(String(30), default="uploaded")
    review_note: Mapped[str] = mapped_column(Text, default="")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))
