from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.audit import audit
from ..core.db import db
from ..core.rbac import require_role
from ..core.security import current_user
from ..models import InsurerAccount, InsurerAccountLink, User
from ..schemas import InsurerAccountIn, InsurerAccountLinkIn, InsurerAccountUpdate
from ..services import insurer_account_dict, serialize

router = APIRouter(prefix="/api", tags=["insurer-accounts"])


@router.get("/insurer-accounts", dependencies=[Depends(require_role("admin", detail="仅总后台可管理收款账户"))])
def insurer_accounts(session: Session = Depends(db)):
    return [insurer_account_dict(x, session) for x in session.scalars(select(InsurerAccount).order_by(InsurerAccount.id.desc()))]


@router.post("/insurer-accounts", dependencies=[Depends(require_role("admin", detail="仅总后台可管理收款账户"))])
def add_insurer_account(data: InsurerAccountIn, user: User = Depends(current_user), session: Session = Depends(db)):
    item = InsurerAccount(**data.model_dump(), status="active")
    session.add(item); session.commit(); session.refresh(item)
    audit(session, user, "create", "insurer_account", str(item.id))
    return insurer_account_dict(item, session)


@router.patch("/insurer-accounts/{item_id}", dependencies=[Depends(require_role("admin", detail="仅总后台可管理收款账户"))])
def update_insurer_account(item_id: int, data: InsurerAccountUpdate, user: User = Depends(current_user), session: Session = Depends(db)):
    item = session.get(InsurerAccount, item_id)
    if not item: raise HTTPException(404, "收款账户不存在")
    for key, value in data.model_dump(exclude_unset=True).items(): setattr(item, key, value)
    session.commit(); audit(session, user, "update", "insurer_account", str(item.id))
    return insurer_account_dict(item, session)


@router.get("/insurer-account-links", dependencies=[Depends(require_role("admin", detail="仅总后台可管理保司映射"))])
def insurer_account_links(session: Session = Depends(db)):
    return [serialize(x) for x in session.scalars(select(InsurerAccountLink).order_by(InsurerAccountLink.id.desc()))]


@router.post("/insurer-account-links", dependencies=[Depends(require_role("admin", detail="仅总后台可管理保司映射"))])
def add_insurer_account_link(data: InsurerAccountLinkIn, user: User = Depends(current_user), session: Session = Depends(db)):
    if not session.get(InsurerAccount, data.account_id): raise HTTPException(404, "收款账户不存在")
    if session.scalar(select(InsurerAccountLink).where(InsurerAccountLink.insurer == data.insurer)):
        raise HTTPException(409, "该保司已绑定收款账户，请先解绑旧映射")
    item = InsurerAccountLink(**data.model_dump())
    session.add(item); session.commit(); session.refresh(item)
    audit(session, user, "create", "insurer_account_link", str(item.id))
    return serialize(item)


@router.delete("/insurer-account-links/{item_id}", dependencies=[Depends(require_role("admin", detail="仅总后台可管理保司映射"))])
def delete_insurer_account_link(item_id: int, user: User = Depends(current_user), session: Session = Depends(db)):
    item = session.get(InsurerAccountLink, item_id)
    if not item: raise HTTPException(404, "映射不存在")
    session.delete(item); session.commit()
    audit(session, user, "delete", "insurer_account_link", str(item_id))
    return {"ok": True}
