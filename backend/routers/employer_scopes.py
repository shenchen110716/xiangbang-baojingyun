from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.db import db
from ..core.security import current_user
from ..models import ActualEmployer, AuditLog, User, UserEmployerScope
from ..schemas import EmployerScopeIn, EmployerScopeOut, PrimaryManagerIn
from ..services import (
    grant_employer_scope,
    is_enterprise_owner,
    replace_primary_manager,
    revoke_employer_scope,
)


router = APIRouter(prefix="/api", tags=["employer-scopes"])


def scope_payload(session: Session, scope: UserEmployerScope) -> dict:
    manager = session.get(User, scope.user_id)
    employer = session.get(ActualEmployer, scope.actual_employer_id)
    return {
        "id": scope.id,
        "user_id": scope.user_id,
        "user_name": manager.name if manager else "",
        "enterprise_id": scope.enterprise_id,
        "actual_employer_id": scope.actual_employer_id,
        "actual_employer_name": employer.name if employer else "",
        "responsibility_type": scope.responsibility_type,
        "assigned_at": scope.assigned_at,
        "revoked_at": scope.revoked_at,
        "status": scope.status,
    }


def _scope_list_enterprise(user: User, enterprise_id: int | None) -> int | None:
    if user.role == "admin":
        return enterprise_id
    if not is_enterprise_owner(user) or not user.enterprise_id:
        raise HTTPException(403, "仅企业主管或平台管理员可查看负责人授权")
    if enterprise_id is not None and enterprise_id != user.enterprise_id:
        raise HTTPException(403, "无权查看其他企业负责人授权")
    return user.enterprise_id


def _add_audit(
    session: Session,
    user: User,
    action: str,
    scope: UserEmployerScope,
    detail: str = "",
) -> None:
    session.add(
        AuditLog(
            user_id=user.id,
            action=action,
            object_type="user_employer_scope",
            object_id=str(scope.id),
            detail=detail,
        )
    )


@router.get("/employer-scopes", response_model=list[EmployerScopeOut])
def employer_scopes(
    enterprise_id: int | None = Query(default=None),
    user: User = Depends(current_user),
    session: Session = Depends(db),
):
    target_enterprise = _scope_list_enterprise(user, enterprise_id)
    stmt = select(UserEmployerScope).order_by(
        UserEmployerScope.actual_employer_id,
        UserEmployerScope.assigned_at.desc(),
        UserEmployerScope.id.desc(),
    )
    if target_enterprise is not None:
        stmt = stmt.where(UserEmployerScope.enterprise_id == target_enterprise)
    return [scope_payload(session, row) for row in session.scalars(stmt)]


@router.post("/employer-scopes", response_model=EmployerScopeOut)
def create_employer_scope(
    data: EmployerScopeIn,
    user: User = Depends(current_user),
    session: Session = Depends(db),
):
    scope = grant_employer_scope(
        session,
        user,
        data.user_id,
        data.actual_employer_id,
        data.responsibility_type,
    )
    _add_audit(session, user, "create", scope, data.responsibility_type)
    session.commit()
    session.refresh(scope)
    return scope_payload(session, scope)


@router.delete("/employer-scopes/{scope_id}", response_model=EmployerScopeOut)
def delete_employer_scope(
    scope_id: int,
    user: User = Depends(current_user),
    session: Session = Depends(db),
):
    scope = revoke_employer_scope(session, user, scope_id)
    _add_audit(session, user, "revoke", scope)
    session.commit()
    session.refresh(scope)
    return scope_payload(session, scope)


@router.post(
    "/actual-employers/{actual_employer_id}/primary-manager",
    response_model=EmployerScopeOut,
)
def set_primary_manager(
    actual_employer_id: int,
    data: PrimaryManagerIn,
    user: User = Depends(current_user),
    session: Session = Depends(db),
):
    employer = session.get(ActualEmployer, actual_employer_id)
    if not employer:
        raise HTTPException(404, "实际工作单位不存在")
    manager = session.get(User, data.user_id)
    if not manager:
        raise HTTPException(404, "项目负责人不存在")
    scope = replace_primary_manager(session, user, employer, manager)
    _add_audit(session, user, "replace_primary", scope, str(actual_employer_id))
    session.commit()
    session.refresh(scope)
    return scope_payload(session, scope)
