from datetime import datetime, timezone
from typing import Optional

from sqlalchemy import DateTime, Float, ForeignKey, Index, String, text
from sqlalchemy.orm import Mapped, mapped_column

from ..core.db import Base


class InsurerAccount(Base):
    # 一个收款账户可以被多个保司共用（见 InsurerAccountLink），所以账户本身
    # 不直接携带 insurer 字段——账户是余额归属的主体，保司只是挂在账户上的
    # 标签。label 供管理员在多个保司共用一个账户时快速识别（如"平安/太平洋
    # 共用账户"）。
    __tablename__ = "insurer_accounts"
    id: Mapped[int] = mapped_column(primary_key=True)
    label: Mapped[str] = mapped_column(String(100), default="")
    bank_name: Mapped[str] = mapped_column(String(100), default="")
    account_no: Mapped[str] = mapped_column(String(60), default="")
    account_holder: Mapped[str] = mapped_column(String(100), default="")
    status: Mapped[str] = mapped_column(String(20), default="active")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))


class InsurerAccountLink(Base):
    # 保司名（对应 InsurancePlan.insurer 的自由文本取值）到收款账户的映射，
    # 多对一：一个保司同一时间只能绑定一个账户，一个账户可以绑定多个保司。
    # 应用层保证同一 insurer 只有一条记录（见 routers/insurer_accounts.py）。
    __tablename__ = "insurer_account_links"
    id: Mapped[int] = mapped_column(primary_key=True)
    insurer: Mapped[str] = mapped_column(String(100))
    insurer_id: Mapped[Optional[int]] = mapped_column(ForeignKey("insurers.id"), nullable=True)
    account_id: Mapped[int] = mapped_column(ForeignKey("insurer_accounts.id"))
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))


class EnterprisePremiumAccount(Base):
    # 余额挂在"企业 + 账户"上，不是"企业 + 保司"——共用账户的保司自然共享
    # 同一笔余额。(enterprise_id, account_id) 唯一由应用层的
    # get_or_create_premium_account() 保证。
    __tablename__ = "enterprise_premium_accounts"
    id: Mapped[int] = mapped_column(primary_key=True)
    enterprise_id: Mapped[int] = mapped_column(ForeignKey("enterprises.id"))
    account_id: Mapped[int] = mapped_column(ForeignKey("insurer_accounts.id"))
    balance: Mapped[float] = mapped_column(Float, default=0)


class RechargeRequest(Base):
    # insurer 是企业提交时选择的那个（仅用于展示/审计），account_id 是后端
    # 据此解析出的实际入账账户——两者在共用账户场景下可能不同保司但同一
    # account_id，这正是"金额不拆分，只判断一个账户余额是否够"的落地方式。
    __tablename__ = "recharge_requests"
    id: Mapped[int] = mapped_column(primary_key=True)
    enterprise_id: Mapped[int] = mapped_column(ForeignKey("enterprises.id"))
    account_type: Mapped[str] = mapped_column(String(20))  # premium / usage
    insurer: Mapped[Optional[str]] = mapped_column(String(100), nullable=True)
    account_id: Mapped[Optional[int]] = mapped_column(ForeignKey("insurer_accounts.id"), nullable=True)
    amount: Mapped[float] = mapped_column(Float, default=0)
    receipt_file_url: Mapped[str] = mapped_column(String(255), default="")
    status: Mapped[str] = mapped_column(String(20), default="pending")  # pending / confirmed / rejected
    reject_reason: Mapped[str] = mapped_column(String(255), default="")
    created_by: Mapped[int] = mapped_column(ForeignKey("users.id"))
    confirmed_by: Mapped[Optional[int]] = mapped_column(ForeignKey("users.id"), nullable=True)
    confirmed_at: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))


class PendingTermination(Base):
    # 保费余额耗尽时惰性扫描生成的待处理停保任务，按账户池化——一个账户
    # 没钱了，挂在它上面的所有保司、所有在保人员都算受影响范围。唯一清除
    # 路径是企业充值后重新扫描发现余额已经 >0，自动 dismiss；管理员没有
    # 手动驳回/忽略的入口——如果不该停保，正确操作是协调企业充值。
    __tablename__ = "pending_terminations"
    __table_args__ = (
        Index(
            "uq_pending_terminations_live_enterprise_account",
            "enterprise_id",
            "account_id",
            unique=True,
            postgresql_where=text("status = 'pending'"),
            sqlite_where=text("status = 'pending'"),
        ),
    )
    id: Mapped[int] = mapped_column(primary_key=True)
    enterprise_id: Mapped[int] = mapped_column(ForeignKey("enterprises.id"))
    account_id: Mapped[int] = mapped_column(ForeignKey("insurer_accounts.id"))
    affected_insurers: Mapped[str] = mapped_column(String(255), default="")
    affected_count: Mapped[int] = mapped_column(default=0)
    status: Mapped[str] = mapped_column(String(20), default="pending")  # pending / confirmed / dismissed
    confirmed_by: Mapped[Optional[int]] = mapped_column(ForeignKey("users.id"), nullable=True)
    confirmed_at: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True)
    dismissed_at: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))
