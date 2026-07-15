from fastapi import HTTPException
from sqlalchemy.orm import Session

from ..models import Enterprise, User


def require_usage_funded(session: Session, enterprise: Enterprise, user: User) -> None:
    """Real-time usage-fee gate for participation-changing endpoints. No
    caching, no precomputation — queries the live enterprise.usage_balance
    value every call, so a just-confirmed recharge unlocks the very next
    request with no separate "unlock" step needed.

    session/user aren't used yet — Task 7 fills in a once-per-day
    notification here that needs both (a DB query for the dedup check, and
    `user` to attribute the resulting AuditLog entry to, since
    AuditLog.user_id is non-nullable and there's no "system" user in this
    codebase). Defining the final signature now means Task 7 only touches
    this function's body, not every call site again."""
    if enterprise.usage_balance <= 0:
        raise HTTPException(403, "使用费余额不足，请先充值后再操作参停保")
