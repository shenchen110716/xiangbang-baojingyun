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
# The whole salesperson portal lives under this prefix (v4.2 §14.4). It is a
# prefix, not an exact set, because some routes carry a path param
# (/api/agent-portal/statements/{id}); every route under it is already
# salesperson-gated at the route level, so allowing the prefix past this global
# guard grants no access the routes themselves wouldn't.
SALESPERSON_ALLOWED_PREFIXES = ("/api/agent-portal/",)

# The insurer portal is not fully self-contained like the salesperson one — it
# reuses six existing shared routers (positions, policies, claims, invoices)
# with narrowed per-request permission, alongside its own /insurer-portal/*
# endpoints. So this is a wider allowlist than SALESPERSON_ALLOWED_*, but every
# path on it is either already role-narrowed at the route level (positions,
# policies, claims, invoices — see Tasks 6/7/9/11) or lives under
# /insurer-portal/ where every route requires require_insurer_scope.
INSURER_ALLOWED_PATHS = {"/api/auth/me", "/api/auth/password", "/api/positions", "/api/claims", "/api/invoices", "/api/policies"}
INSURER_ALLOWED_PREFIXES = ("/api/insurer-portal/", "/api/positions/", "/api/policies/", "/api/claims/")
# /api/insured/{item_id}/insurer-flag (Task 10) is deliberately allowed by
# *suffix*, not by adding a broad "/api/insured/" prefix above — a prefix
# would also open PATCH /api/insured/{id} and PATCH /api/insured/{id}/status
# to the insurer role, which is exactly the boundary this endpoint must not
# cross (insurer may only write a flag reason, never participation status).
# The route itself is separately gated to role="insurer" and to the caller's
# own insurer_id, so allowing this one fixed suffix through grants no access
# beyond that single narrow endpoint.
INSURER_ALLOWED_SUFFIXES = ("/insurer-flag",)


def current_user(request: Request, creds: HTTPAuthorizationCredentials = Depends(security), session: Session = Depends(db)) -> User:
    if not creds: raise HTTPException(status_code=401, detail="请先登录")
    try: payload = jwt.decode(creds.credentials, SECRET_KEY, algorithms=[ALGORITHM]); uid = int(payload["sub"]); token_sv = int(payload.get("sv", -1))
    except Exception: raise HTTPException(status_code=401, detail="登录已过期")
    user = session.get(User, uid)
    if not user or not user.active: raise HTTPException(status_code=401, detail="用户无效")
    if token_sv != user.session_version: raise HTTPException(status_code=401, detail="登录已过期，请重新登录")
    if user.role not in {"admin","enterprise","salesperson","insurer"}: raise HTTPException(status_code=403, detail="该账号暂未开通管理端权限")
    if user.role == "salesperson" and request.url.path not in SALESPERSON_ALLOWED_PATHS and not request.url.path.startswith(SALESPERSON_ALLOWED_PREFIXES): raise HTTPException(status_code=403, detail="业务员账号仅可访问业务员工作台相关接口")
    if user.role == "insurer" and request.url.path not in INSURER_ALLOWED_PATHS and not request.url.path.startswith(INSURER_ALLOWED_PREFIXES) and not request.url.path.endswith(INSURER_ALLOWED_SUFFIXES): raise HTTPException(status_code=403, detail="保司账号仅可访问保司工作台相关接口")
    return user
