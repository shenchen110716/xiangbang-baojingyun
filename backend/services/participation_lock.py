from fastapi import HTTPException
from datetime import timezone
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.business_time import BUSINESS_TIMEZONE, business_now
from ..models import AuditLog, Enterprise, User
from .accruals import usage_account_view
from .notify import notify_enterprise


def require_usage_funded(session: Session, enterprise: Enterprise, user: User) -> None:
    """Real-time usage-fee gate for participation-changing endpoints. No
    caching, no precomputation — recomputes the available usage balance
    (充值总额 − 总使用费) every call, so a just-confirmed recharge unlocks the
    very next request with no separate "unlock" step needed.

    Gating uses the same available-balance figure the three portals display,
    so "余额不足锁定" and the number the enterprise sees never disagree.
    """
    if usage_account_view(session, enterprise)["available"] <= 0:
        _notify_lock_once_per_day(session, enterprise, user)
        raise HTTPException(403, "使用费余额不足，请先充值后再操作参停保")


def _notify_lock_once_per_day(session: Session, enterprise: Enterprise, user: User) -> None:
    local_day_start = business_now().replace(hour=0, minute=0, second=0, microsecond=0)
    today_start = local_day_start.replace(tzinfo=BUSINESS_TIMEZONE).astimezone(timezone.utc).replace(tzinfo=None)
    already_sent = session.scalar(
        select(AuditLog.id).where(
            AuditLog.action == "usage_lock_notify",
            AuditLog.object_type == "enterprise",
            AuditLog.object_id == str(enterprise.id),
            AuditLog.created_at >= today_start,
        ).limit(1)
    )
    if already_sent:
        return
    notify_enterprise(session, enterprise.id, "usage_locked", {})
    session.add(AuditLog(
        user_id=user.id,
        action="usage_lock_notify",
        object_type="enterprise",
        object_id=str(enterprise.id),
        detail="",
    ))
    session.commit()
