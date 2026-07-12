import jwt
from fastapi import Depends, HTTPException
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from passlib.context import CryptContext
from sqlalchemy.orm import Session

from ..models import User
from .config import ALGORITHM, SECRET_KEY
from .db import db

pwd = CryptContext(schemes=["pbkdf2_sha256"], deprecated="auto")
security = HTTPBearer(auto_error=False)


def current_user(creds: HTTPAuthorizationCredentials = Depends(security), session: Session = Depends(db)) -> User:
    if not creds: raise HTTPException(status_code=401, detail="请先登录")
    try: payload = jwt.decode(creds.credentials, SECRET_KEY, algorithms=[ALGORITHM]); uid = int(payload["sub"])
    except Exception: raise HTTPException(status_code=401, detail="登录已过期")
    user = session.get(User, uid)
    if not user or not user.active: raise HTTPException(status_code=401, detail="用户无效")
    if user.role not in {"admin","enterprise"}: raise HTTPException(status_code=403, detail="该账号暂未开通管理端权限")
    return user
