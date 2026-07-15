from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.audit import audit
from ..core.business_time import business_now
from ..core.db import db
from ..core.rbac import require_role
from ..core.security import current_user
from ..models import InsuredPerson, PendingTermination, User
from ..services import serialize, terminate_person_policy

router = APIRouter(prefix="/api", tags=["pending-terminations"])


@router.get(
    "/pending-terminations",
    dependencies=[Depends(require_role("admin", detail="仅总后台可查看待处理停保"))],
)
def pending_terminations(session: Session = Depends(db)):
    return [
        serialize(item)
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
    item = session.get(PendingTermination, item_id)
    if not item:
        raise HTTPException(404, "待处理停保任务不存在")
    if item.status != "pending":
        raise HTTPException(400, "该任务已处理，不能重复确认")

    terminated_at = business_now()
    affected = session.scalars(
        select(InsuredPerson).where(
            InsuredPerson.enterprise_id == item.enterprise_id,
            InsuredPerson.status == "active",
        )
    ).all()
    for person in affected:
        terminate_person_policy(session, person, terminated_at=terminated_at)
        person.status = "stopped"

    item.status = "confirmed"
    item.confirmed_by = user.id
    item.confirmed_at = terminated_at
    session.commit()
    audit(session, user, "confirm", "pending_termination", str(item.id), f"terminated={len(affected)}")
    return {**serialize(item), "terminated_count": len(affected)}
