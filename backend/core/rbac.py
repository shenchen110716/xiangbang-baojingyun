from fastapi import Depends, HTTPException

from ..models import User
from .security import current_user


def require_role(*roles: str, detail: str = "无权访问该资源"):
    def _dependency(user: User = Depends(current_user)) -> User:
        if user.role not in roles:
            raise HTTPException(status_code=403, detail=detail)
        return user
    return _dependency


def assert_enterprise_scope(user: User, enterprise_id: int, detail: str = "无权访问该单位数据") -> None:
    if user.role == "enterprise" and user.enterprise_id != enterprise_id:
        raise HTTPException(status_code=403, detail=detail)


def require_operator_manager(user: User = Depends(current_user)) -> User:
    if user.role == "admin":
        return user
    if user.role == "enterprise" and user.is_owner:
        return user
    raise HTTPException(status_code=403, detail="仅单位主管可管理操作员" if user.role == "enterprise" else "无权管理操作员")
