"""开放平台·账户/流水/发票（story-p1-readonly AC9–AC11，勘误 1）。

余额口径（勘误 1，覆盖设计 §3.3 原文）：保费余额权威是
enterprise_premium_accounts 分账户视图（premium_accounts_for_enterprise），
服务费可用额是 usage_account_view 的计算值；本模块不读
Enterprise.premium_balance / usage_balance 两列。
"""
from datetime import datetime, timezone
from typing import Optional

from fastapi import APIRouter, Depends, Query, Request
from sqlalchemy.orm import Session

from ...core.business_time import BUSINESS_TIMEZONE
from ...core.db import db
from ...models import Invoice, LedgerEntry
from ...services.openapi.auth import OpenApiPrincipal, require_scope
from ...services.openapi.serializers import (
    serialize_accounts, serialize_invoice, serialize_ledger_entry,
)
from .common import E_BIZ, OpenApiError, OpenApiRoute, ok, paginate

router = APIRouter(prefix="/openapi/v1", tags=["开放平台·账户"],
                   route_class=OpenApiRoute)

LEDGER_ACCOUNTS = {"premium", "usage"}


def _parse_date(raw: Optional[str], field: str) -> Optional[datetime]:
    """from/to 过滤：ISO8601 日期或时间戳 → **UTC naive** 比较值。

    review-p1 M1：LedgerEntry.occurred_at 的唯一写入路径落的是 UTC 墙钟
    （models/finance.py 默认 datetime.now(timezone.utc)，naive 列），所以
    过滤值必须折算到 UTC naive 再比较——不带时区的输入按文档声明的北京
    时间（BUSINESS_TIMEZONE）理解，带时区的按其自身偏移，统一转 UTC。"""
    if not raw:
        return None
    try:
        value = datetime.fromisoformat(raw.replace("Z", "+00:00"))
    except ValueError:
        raise OpenApiError(422, E_BIZ,
                           f"无效的 {field}（需 ISO8601 日期或时间戳）") from None
    if value.tzinfo is None:
        value = value.replace(tzinfo=BUSINESS_TIMEZONE)
    return value.astimezone(timezone.utc).replace(tzinfo=None)


@router.get("/accounts")
def get_accounts(
        request: Request,
        principal: OpenApiPrincipal = Depends(require_scope("account:read")),
        session: Session = Depends(db)):
    return ok(serialize_accounts(session, principal.enterprise_id),
              request.state.openapi_request_id)


@router.get("/ledger-entries")
def list_ledger_entries(
        request: Request,
        account: Optional[str] = None,
        # Python 关键字 from 不能做形参名，经 alias 对外仍是 ?from=。
        from_: Optional[str] = Query(None, alias="from"),
        to: Optional[str] = None,
        cursor: Optional[str] = None,
        limit: Optional[int] = None,
        principal: OpenApiPrincipal = Depends(require_scope("account:read")),
        session: Session = Depends(db)):
    query = session.query(LedgerEntry).filter(
        LedgerEntry.enterprise_id == principal.enterprise_id)
    if account is not None:
        if account not in LEDGER_ACCOUNTS:
            raise OpenApiError(422, E_BIZ, "无效的 account，应为 premium/usage")
        query = query.filter(LedgerEntry.account == account)
    start = _parse_date(from_, "from")
    end = _parse_date(to, "to")
    if start is not None:
        query = query.filter(LedgerEntry.occurred_at >= start)
    if end is not None:
        query = query.filter(LedgerEntry.occurred_at <= end)
    rows, next_cursor = paginate(query, cursor, limit)
    return ok({
        "items": [serialize_ledger_entry(entry) for entry in rows],
        "next_cursor": next_cursor,
    }, request.state.openapi_request_id)


@router.get("/invoices")
def list_invoices(
        request: Request,
        cursor: Optional[str] = None,
        limit: Optional[int] = None,
        principal: OpenApiPrincipal = Depends(require_scope("account:read")),
        session: Session = Depends(db)):
    query = session.query(Invoice).filter(
        Invoice.enterprise_id == principal.enterprise_id)
    rows, next_cursor = paginate(query, cursor, limit)
    return ok({
        "items": [serialize_invoice(inv) for inv in rows],
        "next_cursor": next_cursor,
    }, request.state.openapi_request_id)
