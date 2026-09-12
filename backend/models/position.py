from datetime import datetime, timezone
from typing import Optional

from sqlalchemy import DateTime, ForeignKey, Integer, String, Text
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
    # 扫码参保：HR 是否为这个岗位开放"个人缴纳"/"单位缴纳"二维码入口。
    # 两个开关互不影响，一个岗位可以两个都开、都不开、或只开一个。
    enable_personal_pay: Mapped[bool] = mapped_column(default=False)
    enable_employer_pay: Mapped[bool] = mapped_column(default=False)
    # 二维码链接用 HMAC(position_id, mode, 这个版本号) 签名，无状态、不用建表存 token。
    # HR"重新生成二维码"时把这个数字加一，旧二维码的签名立刻对不上，自动失效。
    enroll_token_version: Mapped[int] = mapped_column(Integer, default=0)


class PositionVideo(Base):
    __tablename__ = "position_videos"
    id: Mapped[int] = mapped_column(primary_key=True)
    position_id: Mapped[int] = mapped_column(ForeignKey("work_positions.id"))
    name: Mapped[str] = mapped_column(String(160))
    url: Mapped[str] = mapped_column(Text, default="")
    status: Mapped[str] = mapped_column(String(30), default="pending")
    review_note: Mapped[str] = mapped_column(Text, default="")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))


class PositionEnrollSubmission(Base):
    """扫码参保的提交队列。

    个人缴纳/单位缴纳提交的都是这一张表，不直接生成 InsuredPerson——
    必须经人工审核通过才落地为正式参保记录（回填 insured_person_id）。
    身份证号按 EmploymentFact 的先例加密存储（见 core/id_number.id_encrypt），
    审核通过创建 InsuredPerson 时才解密写成明文。
    """

    __tablename__ = "position_enroll_submissions"
    id: Mapped[int] = mapped_column(primary_key=True)
    position_id: Mapped[int] = mapped_column(ForeignKey("work_positions.id"))
    payment_mode: Mapped[str] = mapped_column(String(20))  # personal / employer
    name: Mapped[str] = mapped_column(String(80), default="")
    id_number_cipher: Mapped[str] = mapped_column(Text, default="")
    phone: Mapped[str] = mapped_column(String(30), default="")
    # not_required（单位缴纳，不涉及支付）/ pending（个人缴纳，等支付）/ paid（个人缴纳已支付）
    payment_status: Mapped[str] = mapped_column(String(20), default="not_required")
    order_no: Mapped[str] = mapped_column(String(60), default="")
    # pending / approved / rejected
    review_status: Mapped[str] = mapped_column(String(20), default="pending")
    review_note: Mapped[str] = mapped_column(Text, default="")
    reviewed_by: Mapped[Optional[int]] = mapped_column(ForeignKey("users.id"), nullable=True)
    reviewed_at: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True)
    insured_person_id: Mapped[Optional[int]] = mapped_column(ForeignKey("insured_people.id"), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))
