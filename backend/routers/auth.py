from datetime import datetime, timedelta, timezone

import jwt
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.audit import audit
from ..core.config import ALGORITHM, SECRET_KEY
from ..core.db import db
from ..core.security import current_user, pwd
from ..models import User
from ..schemas import LoginIn, PasswordChangeIn, TokenOut, UserOut

router = APIRouter(prefix="/api", tags=["auth"])


@router.post("/auth/login", response_model=TokenOut)
def login(data: LoginIn, session: Session = Depends(db)):
    user = session.scalar(select(User).where(User.username == data.username))
    if not user or not pwd.verify(data.password, user.password_hash): raise HTTPException(401, "账号或密码错误")
    if not user.active: raise HTTPException(403, "该账号已停用，请联系单位主管")
    if data.portal == "admin" and user.role != "admin": raise HTTPException(403, "该账号不是总后台账号")
    if data.portal == "enterprise" and user.role != "enterprise": raise HTTPException(403, "该账号不是参保单位账号")
    token = jwt.encode({"sub": str(user.id), "exp": datetime.now(timezone.utc) + timedelta(hours=12)}, SECRET_KEY, algorithm=ALGORITHM)
    return TokenOut(access_token=token)

@router.get("/auth/me", response_model=UserOut)
def me(user: User = Depends(current_user)): return user

@router.patch("/auth/password")
def change_password(data:PasswordChangeIn,user:User=Depends(current_user),session:Session=Depends(db)):
    if not pwd.verify(data.current_password,user.password_hash): raise HTTPException(400,'当前密码不正确')
    if data.current_password==data.new_password: raise HTTPException(400,'新密码不能与当前密码相同')
    user.password_hash=pwd.hash(data.new_password);session.commit();audit(session,user,'password_change','user',str(user.id));return {'ok':True}
