import logging
from datetime import datetime, timezone
from typing import Optional

from sqlalchemy import DateTime, Float, ForeignKey, String, Text, event
from sqlalchemy.orm import Mapped, mapped_column

from ..core.business_time import business_default
from ..core.db import Base
from ..core.id_number import id_decrypt, id_encrypt, id_hash

logger = logging.getLogger(__name__)
# review-p3 m1：解密失败只告警一次（按进程去重），避免批量导出刷屏。
_decrypt_failure_warned = False


class InsuredPerson(Base):
    __tablename__ = "insured_people"
    id: Mapped[int] = mapped_column(primary_key=True)
    enterprise_id: Mapped[int] = mapped_column(ForeignKey("enterprises.id"))
    name: Mapped[str] = mapped_column(String(80))
    phone: Mapped[str] = mapped_column(String(30), default="")
    # story-p3-compliance 双写期：明文列保留作回滚通道（DROP 属 story-p3b），
    # hash 用于查重/等值检索，cipher 用于展示/导出解密。三者由下方 ORM 事件
    # 在同一事务内保持一致，对所有既有写路径透明。
    id_number: Mapped[str] = mapped_column(String(40), default="")
    id_number_hash: Mapped[str] = mapped_column(String(64), default="", index=True)
    id_number_cipher: Mapped[str] = mapped_column(Text, default="")
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

    def decrypted_id_number(self) -> str:
        """展示/导出用明文：先读密文列，密文空或解不开则回退明文列。

        双写期（story-p3-compliance）的权威读姿态：存量未回填行、
        以及极端的密钥错配场景都能退回明文列，保证旧路径不断。
        """
        if self.id_number_cipher:
            try:
                return id_decrypt(self.id_number_cipher)
            except Exception:
                # review-p3 m1：密钥错配不可静默——告警（不含明文/密文本体），
                # 保留回退行为；p3b 删明文列前必须先消除此告警来源。
                global _decrypt_failure_warned
                if not _decrypt_failure_warned:
                    _decrypt_failure_warned = True
                    logger.warning(
                        "insured_person id=%s 身份证密文解密失败，已回退明文列"
                        "（疑似 ID_ENCRYPTION_KEY 错配；本告警每进程只打一次）",
                        self.id)
        return self.id_number or ""


def _sync_id_number_crypto(mapper, connection, target: "InsuredPerson") -> None:
    """同事务双写：明文变化（或密文缺失）时重算 hash + cipher。

    挂在 before_insert/before_update 上，对所有写路径（管理端、批量导入、
    小程序、开放平台申报）透明——它们继续只写 id_number 明文。
    """
    plain = (target.id_number or "").strip()
    if not plain:
        target.id_number_hash = ""
        target.id_number_cipher = ""
        return
    digest = id_hash(plain)
    if target.id_number_hash != digest or not target.id_number_cipher:
        target.id_number_hash = digest
        target.id_number_cipher = id_encrypt(plain)


event.listen(InsuredPerson, "before_insert", _sync_id_number_crypto)
event.listen(InsuredPerson, "before_update", _sync_id_number_crypto)


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
