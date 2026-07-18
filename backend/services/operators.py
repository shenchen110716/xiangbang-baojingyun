from sqlalchemy import or_, select
from sqlalchemy.orm import Session

from ..models import (
    AuditLog,
    Enterprise,
    InsuredPerson,
    RechargeRequest,
    User,
    UserEmployerScope,
)


def account_has_data(session: Session, item: User) -> bool:
    """该单位账号是否产生过有效业务数据。无数据的账号（如误建、从未使用）允许
    平台端全字段修改与删除；有数据的账号只保留有限修改、不可删除，避免破坏
    审计/授权/充值等历史记录的归属。"""
    if session.scalar(select(AuditLog.id).where(AuditLog.user_id == item.id).limit(1)):
        return True
    if session.scalar(select(UserEmployerScope.id).where(UserEmployerScope.granted_by == item.id).limit(1)):
        return True
    if session.scalar(select(RechargeRequest.id).where(or_(RechargeRequest.created_by == item.id, RechargeRequest.confirmed_by == item.id)).limit(1)):
        return True
    if item.is_owner and item.enterprise_id and session.scalar(
        select(InsuredPerson.id).where(InsuredPerson.enterprise_id == item.enterprise_id).limit(1)
    ):
        return True
    return False


def operator_dict(item: User, session: Session):
    enterprise = session.get(Enterprise, item.enterprise_id) if item.enterprise_id else None
    return {
        "id": item.id, "username": item.username, "name": item.name, "phone": item.phone,
        "role": item.role, "enterprise_id": item.enterprise_id,
        "enterprise_name": enterprise.name if enterprise else "",
        "enterprise_role": item.enterprise_role, "is_owner": item.is_owner,
        "active": item.active, "created_at": item.created_at,
        # 无有效数据的账号允许全字段修改与删除（前端据此放开用户名编辑/删除按钮）。
        "has_data": account_has_data(session, item),
    }
