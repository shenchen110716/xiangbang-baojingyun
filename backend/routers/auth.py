from datetime import datetime, timedelta, timezone

import jwt
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.audit import audit
from ..core.config import ALGORITHM, SECRET_KEY
from ..core.db import db
from ..core.security import current_user, pwd
from ..models import Enterprise, User
from ..schemas import LoginIn, PasswordChangeIn, TokenOut, UserOut

router = APIRouter(prefix="/api", tags=["auth"])


def _issue_token(user: User) -> str:
    return jwt.encode({"sub": str(user.id), "sv": user.session_version, "exp": datetime.now(timezone.utc) + timedelta(hours=12)}, SECRET_KEY, algorithm=ALGORITHM)


@router.post("/auth/login", response_model=TokenOut)
def login(data: LoginIn, session: Session = Depends(db)):
    user = session.scalar(select(User).where(User.username == data.username))
    if not user or not pwd.verify(data.password, user.password_hash): raise HTTPException(401, "账号或密码错误")
    if not user.active: raise HTTPException(403, "该账号已停用，请联系单位主管")
    if data.portal == "admin" and user.role != "admin": raise HTTPException(403, "该账号不是总后台账号")
    if data.portal == "enterprise" and user.role != "enterprise": raise HTTPException(403, "该账号不是参保单位账号")
    return TokenOut(access_token=_issue_token(user))

@router.get("/auth/me", response_model=UserOut)
def me(user: User = Depends(current_user)): return user

def _linked_accounts_query(session: Session, user: User):
    if user.role != "enterprise" or not user.is_owner or not user.phone.strip():
        return []
    return session.scalars(select(User).where(User.role == "enterprise", User.is_owner.is_(True), User.phone == user.phone, User.id != user.id, User.active.is_(True))).all()

@router.get("/auth/linked-accounts")
def linked_accounts(user: User = Depends(current_user), session: Session = Depends(db)):
    """Other enterprise-owner accounts sharing this user's phone number — the
    real-world case being one person who is 负责人 for multiple 参保单位, each
    with its own separate account (see feedback item 11). Lets the web app
    offer an in-app switcher instead of requiring logout/login per company."""
    accounts = _linked_accounts_query(session, user)
    result = []
    for item in accounts:
        enterprise = session.get(Enterprise, item.enterprise_id) if item.enterprise_id else None
        result.append({"id": item.id, "name": item.name, "enterprise_id": item.enterprise_id, "enterprise_name": enterprise.name if enterprise else "", "created_at": enterprise.created_at if enterprise else None})
    return result

@router.post("/auth/switch-account")
def switch_account(target_user_id: int, user: User = Depends(current_user), session: Session = Depends(db)):
    accounts = _linked_accounts_query(session, user)
    target = next((item for item in accounts if item.id == target_user_id), None)
    if not target: raise HTTPException(403, "无权切换到该账号")
    audit(session, user, "switch_account", "user", str(target.id))
    return TokenOut(access_token=_issue_token(target))

@router.patch("/auth/password")
def change_password(data:PasswordChangeIn,user:User=Depends(current_user),session:Session=Depends(db)):
    if not pwd.verify(data.current_password,user.password_hash): raise HTTPException(400,'当前密码不正确')
    if data.current_password==data.new_password: raise HTTPException(400,'新密码不能与当前密码相同')
    user.password_hash=pwd.hash(data.new_password);user.session_version+=1;session.commit();audit(session,user,'password_change','user',str(user.id));return {'ok':True}
