from sqlalchemy import select
from sqlalchemy.orm import Session

from ..models import EnterprisePremiumAccount, InsurerAccount, InsurerAccountLink
from .serialization import amount, serialize


def resolve_account_for_insurer(session: Session, insurer: str) -> InsurerAccount | None:
    link = session.scalar(select(InsurerAccountLink).where(InsurerAccountLink.insurer == insurer))
    if not link:
        return None
    account = session.get(InsurerAccount, link.account_id)
    return account if account and account.status == "active" else None


def insurers_for_account(session: Session, account_id: int) -> list[str]:
    return [row[0] for row in session.execute(select(InsurerAccountLink.insurer).where(InsurerAccountLink.account_id == account_id)).all()]


def insurer_account_dict(item: InsurerAccount, session: Session) -> dict:
    return {**serialize(item), "insurers": insurers_for_account(session, item.id)}


def get_or_create_premium_account(session: Session, enterprise_id: int, account_id: int) -> EnterprisePremiumAccount:
    row = session.scalar(select(EnterprisePremiumAccount).where(EnterprisePremiumAccount.enterprise_id == enterprise_id, EnterprisePremiumAccount.account_id == account_id))
    if row:
        return row
    row = EnterprisePremiumAccount(enterprise_id=enterprise_id, account_id=account_id, balance=0)
    session.add(row)
    session.flush()
    return row


def premium_accounts_for_enterprise(session: Session, enterprise_id: int) -> list[dict]:
    rows = session.scalars(select(EnterprisePremiumAccount).where(EnterprisePremiumAccount.enterprise_id == enterprise_id))
    result = []
    for row in rows:
        account = session.get(InsurerAccount, row.account_id)
        if not account:
            continue
        result.append({
            "account_id": row.account_id,
            "label": account.label,
            "insurers": insurers_for_account(session, row.account_id),
            "balance": amount(row.balance),
        })
    return result
