from fastapi import APIRouter, Depends
from pydantic import BaseModel

from ..core.audit import audit
from ..core.db import db
from ..core.rbac import require_role
from ..core.security import current_user
from ..models import User
from ..services import settings as settings_service
from sqlalchemy.orm import Session

router = APIRouter(prefix="/api", tags=["system-settings"])


class SettingsUpdateIn(BaseModel):
    values: dict[str, str]


@router.get("/system-settings", dependencies=[Depends(require_role("admin", detail="仅平台端可查看系统设置"))])
def get_system_settings():
    """按分组返回设置项；密钥项只返回是否已配置 + 掩码，绝不回传明文。"""
    return {"groups": settings_service.admin_view()}


@router.put("/system-settings", dependencies=[Depends(require_role("admin", detail="仅平台端可修改系统设置"))])
def update_system_settings(data: SettingsUpdateIn, user: User = Depends(current_user), session: Session = Depends(db)):
    settings_service.set_many(data.values, user.id)
    audit(session, user, "update", "system_settings", "-", ",".join(sorted(data.values.keys())))
    return {"groups": settings_service.admin_view()}
