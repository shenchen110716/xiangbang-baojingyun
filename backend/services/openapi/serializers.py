"""开放平台 P1 只读面的查询与序列化（story-p1-readonly AC2–AC5、AC9–AC13）。

- 证件号脱敏按 AC4 的口径：前 3 位 + * 填充 + 后 4 位（如
  110***********1234）。注意这与 core/id_number.mask_id_number（前 6 后 4，
  内部审计口径）不同——对外面更保守，前 3 位连出生地级市都不暴露。
- 状态语义映射（设计 §3.2）：draft→未参保、pending→待生效、active→在保、
  stopped→已停保；内部码与对外语义同时返回（AC3）。
- 账户口径按勘误 1：保费余额权威是 enterprise_premium_accounts 分账户，
  可用服务费 = usage_account_view 计算值；绝不读
  Enterprise.premium_balance / usage_balance 两列。
- 所有查询显式 WHERE enterprise_id = key.enterprise_id（红线），本模块的
  query 构造函数都强制要求 enterprise_id 参数。
"""
import json
from datetime import datetime, timezone
from typing import Optional

from sqlalchemy import exists, or_, select
from sqlalchemy.orm import Session

from ...core.business_time import as_business_time
from ...models import (
    Enterprise, InsuredPerson, Invoice, LedgerEntry, ParticipationOperation,
    Policy, PolicyMember,
)
from ...routers.openapi.common import E_BIZ, OpenApiError
from ..accruals import usage_account_view
from ..recharge import premium_accounts_for_enterprise
from ..serialization import amount

# 内部状态码 → 对外语义（设计 §3.2 / AC3）。
INSURED_STATUS_SEMANTICS = {
    "draft": "未参保",
    "pending": "待生效",
    "active": "在保",
    "stopped": "已停保",
}


def mask_id_number_openapi(value: str) -> str:
    """AC4 对外脱敏口径：前 3 位 + * 填充 + 后 4 位。短于 8 位全打码。"""
    raw = (value or "").strip().upper()
    if not raw:
        return ""
    if len(raw) < 8:
        return "*" * len(raw)
    return f'{raw[:3]}{"*" * (len(raw) - 7)}{raw[-4:]}'


def _iso(value: Optional[datetime]) -> Optional[str]:
    return value.isoformat() if value is not None else None


def _iso_utc(value: Optional[datetime]) -> Optional[str]:
    """naive-UTC 列（如 LedgerEntry.occurred_at）输出时补 +00:00 偏移量，
    免得被对接方按本地/北京时间误读（review-p1 M1 同根因）。"""
    if value is None:
        return None
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    return value.isoformat()


def parse_updated_since(raw: Optional[str]) -> Optional[datetime]:
    """ISO8601 → aware UTC；非法输入 422（统一包 42200）。缺时区按 UTC。"""
    if not raw:
        return None
    try:
        value = datetime.fromisoformat(raw.replace("Z", "+00:00"))
    except ValueError:
        raise OpenApiError(422, E_BIZ, "无效的 updated_since（需 ISO8601 时间戳）") from None
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    return value


def insured_persons_query(session: Session, enterprise_id: int, *,
                          status: Optional[str] = None,
                          position_id: Optional[int] = None,
                          updated_since: Optional[datetime] = None):
    """名单查询（AC2）；列表端点与异步导出共用同一套过滤语义。

    updated_since 的"更新"没有现成列（insured_people 无 updated_at），按
    业务事件口径近似：人员建档、保障期写入/终止、参停保操作任一发生在
    updated_since 之后即视为有更新。naive 列用业务时钟（北京）口径比较，
    tz-aware 列用 UTC。
    """
    query = session.query(InsuredPerson).filter(
        InsuredPerson.enterprise_id == enterprise_id)
    if status is not None:
        if status not in INSURED_STATUS_SEMANTICS:
            raise OpenApiError(
                422, E_BIZ,
                f"无效的 status，应为 {'/'.join(INSURED_STATUS_SEMANTICS)}")
        query = query.filter(InsuredPerson.status == status)
    if position_id is not None:
        query = query.filter(InsuredPerson.position_id == position_id)
    if updated_since is not None:
        naive = as_business_time(updated_since)
        member_touched = exists(
            select(PolicyMember.id).where(
                PolicyMember.person_id == InsuredPerson.id,
                or_(PolicyMember.created_at >= naive,
                    PolicyMember.effective_at >= naive,
                    PolicyMember.terminated_at >= naive)))
        operation_touched = exists(
            select(ParticipationOperation.id).where(
                ParticipationOperation.person_id == InsuredPerson.id,
                ParticipationOperation.enterprise_id == enterprise_id,
                or_(ParticipationOperation.submitted_at >= updated_since,
                    ParticipationOperation.insurer_confirmed_at >= updated_since)))
        query = query.filter(or_(InsuredPerson.created_at >= naive,
                                 member_touched, operation_touched))
    return query


def current_coverage(session: Session, person: InsuredPerson) -> Optional[dict]:
    """当前 PolicyMember 保障期间（AC5）：取该人最近一条保障期记录。"""
    member = session.scalar(
        select(PolicyMember).where(PolicyMember.person_id == person.id)
        .order_by(PolicyMember.id.desc()).limit(1))
    if member is None:
        return None
    policy = session.get(Policy, member.policy_id)
    return {
        "policy_id": member.policy_id,
        "policy_no": policy.policy_no if policy else None,
        "effective_at": _iso(member.effective_at),
        "terminated_at": _iso(member.terminated_at),
        "status": member.status,
    }


def serialize_insured_person(person: InsuredPerson, *, sensitive: bool) -> dict:
    return {
        "id": person.id,
        "name": person.name,
        "phone": person.phone,
        "id_number": (person.id_number if sensitive
                      else mask_id_number_openapi(person.id_number)),
        "status": person.status,
        "status_label": INSURED_STATUS_SEMANTICS.get(person.status, person.status),
        "position_id": person.position_id,
        "occupation": person.occupation,
        "occupation_class": person.occupation_class,
        "created_at": _iso(person.created_at),
    }


def serialize_operation(op: ParticipationOperation) -> dict:
    return {
        "id": op.id,
        "operation_type": op.operation_type,
        "submitted_at": _iso(op.submitted_at),
        "expected_at": _iso(op.expected_at),
        "insurer_confirmed_at": _iso(op.insurer_confirmed_at),
    }


def serialize_policy(policy: Policy) -> dict:
    """设计 §3.4 的字段面：policy_no、起止、状态；不含任何定价内部字段
    （返佣/底价/利润属 /api 面的 strip_internal_pricing 体系，不出开放面）。"""
    return {
        "id": policy.id,
        "policy_no": policy.policy_no,
        "plan_id": policy.plan_id,
        "status": policy.status,
        "start_date": policy.start_date,
        "end_date": policy.end_date,
        "has_document": bool(policy.document_url),
        "document_name": policy.document_name or None,
        "created_at": _iso(policy.created_at),
    }


def serialize_accounts(session: Session, enterprise_id: int) -> dict:
    """账户视图（AC9，勘误 1）：分账户保费余额 + 服务费可用额计算值。

    权威来源：premium_accounts_for_enterprise()（enterprise_premium_accounts
    分账户，已剔除平台使用费保留账户）与 usage_account_view()（可用额 =
    充值累计 − 全时段人天 × 日费率）。不读 Enterprise.premium_balance /
    usage_balance 两列。
    """
    enterprise = session.get(Enterprise, enterprise_id)
    if enterprise is None:
        raise OpenApiError(404, E_BIZ, "资源不存在")
    return {
        "premium_accounts": premium_accounts_for_enterprise(session, enterprise_id),
        "usage": usage_account_view(session, enterprise),
    }


def serialize_ledger_entry(entry: LedgerEntry) -> dict:
    return {
        "id": entry.id,
        "account": entry.account,
        "account_id": entry.account_id,
        "direction": entry.direction,
        "amount": amount(float(entry.amount)),
        "business_type": entry.business_type,
        "business_id": entry.business_id,
        "occurred_at": _iso_utc(entry.occurred_at),
    }


def serialize_invoice(invoice: Invoice) -> dict:
    return {
        "id": invoice.id,
        "account": invoice.account,
        "invoice_type": invoice.invoice_type,
        "amount": amount(invoice.amount),
        "title": invoice.title,
        "status": invoice.status,
        "has_document": bool(invoice.document_url),
        "created_at": _iso(invoice.created_at),
    }


def export_params(*, status: Optional[str], position_id: Optional[int],
                  updated_since: Optional[str], sensitive: bool) -> str:
    """导出任务参数快照（AC7）：过滤条件 + 入队那一刻的脱敏口径。"""
    return json.dumps({
        "status": status,
        "position_id": position_id,
        "updated_since": updated_since,
        "sensitive": sensitive,
    }, ensure_ascii=False)
