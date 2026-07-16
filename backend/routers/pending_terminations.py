from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select, update
from sqlalchemy.orm import Session

from ..core.audit import audit
from ..core.business_time import business_now
from ..core.db import db
from ..core.rbac import require_role
from ..core.security import current_user
from ..models import Enterprise, EnterprisePremiumAccount, InsurerAccount, PendingTermination, User
from ..services import (
    affected_people_for_account,
    notify_enterprise,
    scan_premium_shortfalls,
    serialize,
    terminate_person_policy,
)
from ..services.termination_scan import affected_coverage_for_account

router = APIRouter(prefix="/api", tags=["pending-terminations"])


def _pending_dict(item: PendingTermination, session: Session) -> dict:
    enterprise = session.get(Enterprise, item.enterprise_id)
    account = session.get(InsurerAccount, item.account_id)
    _, affected_people = affected_people_for_account(
        session,
        item.enterprise_id,
        item.account_id,
    ) if item.status == "pending" else ([], [])
    return {
        **serialize(item),
        "enterprise_name": enterprise.name if enterprise else "",
        "account_label": account.label if account else "",
        "current_affected_count": len(affected_people) if item.status == "pending" else item.affected_count,
        "affected_people": [{"id": person.id, "name": person.name} for person in affected_people],
    }


@router.get(
    "/pending-terminations",
    dependencies=[Depends(require_role("admin", detail="仅总后台可查看待处理停保"))],
)
def pending_terminations(session: Session = Depends(db)):
    scan_premium_shortfalls(session)
    return [
        _pending_dict(item, session)
        for item in session.scalars(select(PendingTermination).order_by(PendingTermination.id.desc()))
    ]


@router.post(
    "/pending-terminations/{item_id}/confirm",
    dependencies=[Depends(require_role("admin", detail="仅总后台可确认停保"))],
)
def confirm_pending_termination(
    item_id: int,
    user: User = Depends(current_user),
    session: Session = Depends(db),
):
    claimed = session.execute(
        update(PendingTermination)
        .where(
            PendingTermination.id == item_id,
            PendingTermination.status == "pending",
        )
        .values(status="processing")
    )
    if claimed.rowcount != 1:
        session.rollback()
        if session.get(PendingTermination, item_id) is None:
            raise HTTPException(404, "待处理停保任务不存在")
        raise HTTPException(400, "该任务已处理，不能重复确认")
    item = session.get(PendingTermination, item_id)

    premium_account = session.scalar(
        select(EnterprisePremiumAccount)
        .where(
            EnterprisePremiumAccount.enterprise_id == item.enterprise_id,
            EnterprisePremiumAccount.account_id == item.account_id,
        )
        .with_for_update()
    )
    if premium_account is None or premium_account.balance > 0:
        item.status = "dismissed"
        item.dismissed_at = business_now()
        session.commit()
        audit(session, user, "auto_dismiss", "pending_termination", str(item.id), "balance_recovered")
        raise HTTPException(409, "该账户已充值，待停保任务已自动撤销")

    insurers, affected_coverage = affected_coverage_for_account(
        session,
        item.enterprise_id,
        item.account_id,
    )
    affected = [person for person, _member in affected_coverage]
    if not affected:
        item.status = "dismissed"
        item.dismissed_at = business_now()
        session.commit()
        audit(session, user, "auto_dismiss", "pending_termination", str(item.id), "no_affected_people")
        raise HTTPException(409, "该账户当前没有可停保人员，任务已自动撤销")

    terminated_at = business_now()
    for person, member in affected_coverage:
        terminate_person_policy(
            session,
            person,
            terminated_at=terminated_at,
            enforce_timing=False,
            coverage_member_id=member.id,
        )
        person.status = "stopped"

    item.affected_insurers = ",".join(insurers)
    item.affected_count = len(affected)
    item.status = "confirmed"
    item.confirmed_by = user.id
    item.confirmed_at = terminated_at
    session.commit()
    audit(session, user, "confirm", "pending_termination", str(item.id), f"terminated={len(affected)}")
    notify_enterprise(
        session,
        item.enterprise_id,
        "termination_confirmed",
        {"insurers": item.affected_insurers, "terminated_count": len(affected)},
    )
    return {**_pending_dict(item, session), "terminated_count": len(affected)}
