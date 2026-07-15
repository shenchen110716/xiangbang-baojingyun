import jwt
from fastapi import Depends, HTTPException, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from passlib.context import CryptContext
from sqlalchemy.orm import Session

from ..models import User
from .config import ALGORITHM, SECRET_KEY
from .db import db

pwd = CryptContext(schemes=["pbkdf2_sha256"], deprecated="auto")
security = HTTPBearer(auto_error=False)

# Every other role check in this codebase is *negative* scoping ("if
# enterprise, restrict to own data; otherwise full access") which silently
# treats any role that isn't "enterprise" as admin-equivalent. That's safe
# only because "admin"/"enterprise" were the only roles that could ever
# reach past current_user(). salesperson accounts must NOT fall through
# those checks, so they're allowlisted here to only the handful of
# self-service endpoints the salesperson portal actually needs, rather than
# being trusted to fail closed on every other router in the codebase.
SALESPERSON_ALLOWED_PATHS = {"/api/agents/me", "/api/auth/me", "/api/auth/password"}


def current_user(request: Request, creds: HTTPAuthorizationCredentials = Depends(security), session: Session = Depends(db)) -> User:
    if not creds: raise HTTPException(status_code=401, detail="请先登录")
    try: payload = jwt.decode(creds.credentials, SECRET_KEY, algorithms=[ALGORITHM]); uid = int(payload["sub"]); token_sv = int(payload.get("sv", -1))
    except Exception: raise HTTPException(status_code=401, detail="登录已过期")
    user = session.get(User, uid)
    if not user or not user.active: raise HTTPException(status_code=401, detail="用户无效")
    if token_sv != user.session_version: raise HTTPException(status_code=401, detail="登录已过期，请重新登录")
    if user.role not in {"admin","enterprise","salesperson"}: raise HTTPException(status_code=403, detail="该账号暂未开通管理端权限")
    if user.role == "salesperson" and request.url.path not in SALESPERSON_ALLOWED_PATHS: raise HTTPException(status_code=403, detail="业务员账号仅可访问业务员工作台相关接口")
    return user
