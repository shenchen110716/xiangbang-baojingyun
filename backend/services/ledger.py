from decimal import Decimal
from typing import Literal, Optional

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from ..models import Enterprise, LedgerEntry, User
from .serialization import amount, serialize


def post_ledger_entry(
    session: Session,
    enterprise: Enterprise,
    account: Literal["premium", "usage"],
    direction: Literal["credit", "debit"],
    value: float,
    business_type: str,
    business_id: str = "",
    user: Optional[User] = None,
    idempotency_key: str = "",
) -> LedgerEntry:
    # Caller is responsible for updating enterprise.premium_balance /
    # enterprise.usage_balance and calling session.commit() in the SAME
    # transaction as this insert — that's what keeps the cached balance
    # (BalanceSnapshot) and the ledger from drifting apart. See
    # reconcile_enterprise_ledger() below for the periodic cross-check.
    entry = LedgerEntry(
        enterprise_id=enterprise.id,
        account=account,
        direction=direction,
        amount=Decimal(str(amount(value))),
        business_type=business_type,
        business_id=business_id,
        idempotency_key=idempotency_key,
        created_by=user.id if user else None,
    )
    session.add(entry)
    return entry


def ledger_dict(item: LedgerEntry, session: Session) -> dict:
    operator = session.get(User, item.created_by) if item.created_by else None
    return {**serialize(item), "amount": float(item.amount), "operator": operator.name if operator else "系统"}


def reconcile_enterprise_ledger(session: Session, enterprise: Enterprise) -> list[dict]:
    """Compare SUM(LedgerEntry) against the cached balance columns for one
    enterprise's two accounts. Returns a list of mismatches (empty if the
    books balance). This is the manual/on-demand form of the periodic
    reconciliation job described in SYSTEM-DESIGN-V4.md section 7.5 —
    wiring it into an actual scheduled job is Phase 3 (Outbox/Worker)
    scope, which doesn't exist in this codebase yet."""
    mismatches = []
    for account, cached in (("premium", enterprise.premium_balance), ("usage", enterprise.usage_balance)):
        credit = session.scalar(select(func.coalesce(func.sum(LedgerEntry.amount), 0)).where(LedgerEntry.enterprise_id == enterprise.id, LedgerEntry.account == account, LedgerEntry.direction == "credit")) or Decimal(0)
        debit = session.scalar(select(func.coalesce(func.sum(LedgerEntry.amount), 0)).where(LedgerEntry.enterprise_id == enterprise.id, LedgerEntry.account == account, LedgerEntry.direction == "debit")) or Decimal(0)
        ledger_balance = amount(float(credit - debit))
        if ledger_balance != amount(cached):
            mismatches.append({"account": account, "cached_balance": amount(cached), "ledger_balance": ledger_balance, "diff": amount(cached - ledger_balance)})
    return mismatches
