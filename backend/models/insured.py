from datetime import datetime, timezone
from typing import Optional

from sqlalchemy import DateTime, Float, ForeignKey, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from ..core.business_time import business_default
from ..core.db import Base


class InsuredPerson(Base):
    __tablename__ = "insured_people"
    id: Mapped[int] = mapped_column(primary_key=True)
    enterprise_id: Mapped[int] = mapped_column(ForeignKey("enterprises.id"))
    name: Mapped[str] = mapped_column(String(80))
    phone: Mapped[str] = mapped_column(String(30), default="")
    id_number: Mapped[str] = mapped_column(String(40), default="")
    occupation: Mapped[str] = mapped_column(String(80), default="")
    occupation_class: Mapped[str] = mapped_column(String(20), default="3类")
    position_id: Mapped[Optional[int]] = mapped_column(ForeignKey("work_positions.id"), nullable=True)
    status: Mapped[str] = mapped_column(String(30), default="pending")
    # 保司标注的异常原因（"参保/停保信息有问题"），空字符串表示当前没有标注。
    # 只能由 role='insurer' 通过 PATCH /insured/{id}/insurer-flag 写入/清空，
    # 企业端和平台端的参保状态本身不受影响——见 2026-07-24 设计文档"范围边界"。
    insurer_flag_reason: Mapped[str] = mapped_column(Text, default="")
    insurer_flagged_at: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True)
    insurer_flagged_by: Mapped[Optional[int]] = mapped_column(ForeignKey("users.id"), nullable=True)
    policy_id: Mapped[Optional[int]] = mapped_column(ForeignKey("policies.id"), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=business_default)


class Policy(Base):
    __tablename__ = "policies"
    id: Mapped[int] = mapped_column(primary_key=True)
    policy_no: Mapped[str] = mapped_column(String(80), unique=True)
    enterprise_id: Mapped[int] = mapped_column(ForeignKey("enterprises.id"))
    plan_id: Mapped[int] = mapped_column(ForeignKey("insurance_plans.id"))
    premium: Mapped[float] = mapped_column(Float, default=0)
    status: Mapped[str] = mapped_column(String(30), default="active")
    start_date: Mapped[str] = mapped_column(String(20), default="")
    end_date: Mapped[str] = mapped_column(String(20), default="")
    # 保司出具的真实保单文件（PDF/图片），由平台端手工导入，与系统自动生成的
    # policy_no（内部编号）是两回事——见反馈条目 4「平台端要有保单导入功能」。
    document_url: Mapped[str] = mapped_column(Text, default="")
    document_name: Mapped[str] = mapped_column(String(200), default="")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=business_default)


class PolicyMember(Base):
    # SYSTEM-DESIGN-V4.md v4.1 section 7.4 — carries the coverage-period
    # semantics that were originally going to be a separate CoveragePeriod
    # entity before that got merged into PolicyMember during the design
    # brainstorm. tenant_id and enrollment_request_id from the doc's full
    # shape are intentionally omitted: there's no Tenant table (LedgerEntry
    # made the same call) and no EnrollmentRequest table this round (that's
    # the full state-machine rewrite, deferred — see plan doc).
    __tablename__ = "policy_members"
    id: Mapped[int] = mapped_column(primary_key=True)
    policy_id: Mapped[int] = mapped_column(ForeignKey("policies.id"))
    person_id: Mapped[int] = mapped_column(ForeignKey("insured_people.id"), index=True)
    rate_snapshot_json: Mapped[str] = mapped_column(Text, default="")
    effective_at: Mapped[datetime] = mapped_column(DateTime, default=business_default)
    terminated_at: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True)
    endorsement_no: Mapped[str] = mapped_column(String(80), default="")
    status: Mapped[str] = mapped_column(String(20), default="active")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=business_default)
