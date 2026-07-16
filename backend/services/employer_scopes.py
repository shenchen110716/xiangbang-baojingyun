from datetime import datetime, timezone

from fastapi import HTTPException
from sqlalchemy import or_, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from ..models import ActualEmployer, User, UserEmployerScope


RESPONSIBILITY_TYPES = {"primary", "collaborator"}


def is_enterprise_owner(user: User) -> bool:
    return user.role == "enterprise" and (
        user.enterprise_role == "owner" or user.is_owner
    )


def allowed_employer_ids(session: Session, user: User) -> set[int] | None:
    if user.role == "admin":
        return None
    if is_enterprise_owner(user):
        if not user.enterprise_id:
            raise HTTPException(403, "企业主管账号未绑定投保单位")
        return None
    if user.role != "enterprise" or user.enterprise_role != "project_manager":
        raise HTTPException(403, "无权访问实际工作单位数据")
    if not user.enterprise_id:
        return set()
    return set(
        session.scalars(
            select(UserEmployerScope.actual_employer_id).where(
                UserEmployerScope.user_id == user.id,
                UserEmployerScope.enterprise_id == user.enterprise_id,
                UserEmployerScope.status == "active",
                UserEmployerScope.revoked_at.is_(None),
            )
        )
    )


def assert_employer_access(
    session: Session, user: User, actual_employer_id: int
) -> ActualEmployer:
    employer = session.get(ActualEmployer, actual_employer_id)
    if not employer:
        raise HTTPException(404, "实际工作单位不存在")
    if user.role == "enterprise" and employer.enterprise_id != user.enterprise_id:
        raise HTTPException(403, "无权访问其他企业数据")
    allowed = allowed_employer_ids(session, user)
    if allowed is not None and employer.id not in allowed:
        raise HTTPException(403, "未获授权访问该实际工作单位")
    return employer


def assert_scope_manager(actor: User, enterprise_id: int) -> None:
    if actor.role == "admin":
        return
    if not is_enterprise_owner(actor) or actor.enterprise_id != enterprise_id:
        raise HTTPException(403, "仅本企业主管可管理项目负责人授权")


def _project_manager(
    session: Session, user_id: int, enterprise_id: int
) -> User:
    manager = session.get(User, user_id)
    if not manager or manager.role != "enterprise":
        raise HTTPException(404, "项目负责人不存在")
    if manager.enterprise_id != enterprise_id:
        raise HTTPException(403, "项目负责人和实际工作单位不属于同一企业")
    if manager.enterprise_role != "project_manager" or manager.is_owner:
        raise HTTPException(400, "只能给项目负责人分配实际工作单位")
    if not manager.active:
        raise HTTPException(400, "项目负责人账号已停用")
    return manager


def _flush_scope(session: Session, scope: UserEmployerScope) -> UserEmployerScope:
    session.add(scope)
    try:
        session.flush()
    except IntegrityError as error:
        session.rollback()
        raise HTTPException(409, "授权冲突，请刷新后重试") from error
    return scope


def grant_employer_scope(
    session: Session,
    actor: User,
    user_id: int,
    actual_employer_id: int,
    responsibility_type: str,
) -> UserEmployerScope:
    if responsibility_type not in RESPONSIBILITY_TYPES:
        raise HTTPException(400, "负责人类型不合法")
    employer = session.get(ActualEmployer, actual_employer_id)
    if not employer:
        raise HTTPException(404, "实际工作单位不存在")
    assert_scope_manager(actor, employer.enterprise_id)
    manager = _project_manager(session, user_id, employer.enterprise_id)

    duplicate = session.scalar(
        select(UserEmployerScope).where(
            UserEmployerScope.user_id == manager.id,
            UserEmployerScope.actual_employer_id == employer.id,
            UserEmployerScope.status == "active",
            UserEmployerScope.revoked_at.is_(None),
        )
    )
    if duplicate:
        raise HTTPException(409, "该项目负责人已有此工作单位的有效授权")
    if responsibility_type == "primary":
        existing_primary = session.scalar(
            select(UserEmployerScope).where(
                UserEmployerScope.actual_employer_id == employer.id,
                UserEmployerScope.responsibility_type == "primary",
                UserEmployerScope.status == "active",
                UserEmployerScope.revoked_at.is_(None),
            )
        )
        if existing_primary:
            raise HTTPException(409, "该实际工作单位已有主要负责人，请使用更换主要负责人")

    now = datetime.now(timezone.utc)
    return _flush_scope(
        session,
        UserEmployerScope(
            user_id=manager.id,
            enterprise_id=employer.enterprise_id,
            actual_employer_id=employer.id,
            responsibility_type=responsibility_type,
            granted_by=actor.id,
            assigned_at=now,
            status="active",
            created_at=now,
        ),
    )


def revoke_employer_scope(
    session: Session, actor: User, scope_id: int
) -> UserEmployerScope:
    scope = session.get(UserEmployerScope, scope_id)
    if not scope:
        raise HTTPException(404, "负责人授权不存在")
    assert_scope_manager(actor, scope.enterprise_id)
    if scope.status != "active" or scope.revoked_at is not None:
        raise HTTPException(409, "该负责人授权已经撤销")
    scope.status = "revoked"
    scope.revoked_at = datetime.now(timezone.utc)
    session.flush()
    return scope


def replace_primary_manager(
    session: Session,
    actor: User,
    employer: ActualEmployer,
    manager: User,
) -> UserEmployerScope:
    assert_scope_manager(actor, employer.enterprise_id)
    manager = _project_manager(session, manager.id, employer.enterprise_id)
    now = datetime.now(timezone.utc)
    active = session.scalars(
        select(UserEmployerScope)
        .where(
            UserEmployerScope.actual_employer_id == employer.id,
            UserEmployerScope.status == "active",
            UserEmployerScope.revoked_at.is_(None),
            or_(
                UserEmployerScope.responsibility_type == "primary",
                UserEmployerScope.user_id == manager.id,
            ),
        )
        .with_for_update()
    ).all()
    for row in active:
        row.status = "revoked"
        row.revoked_at = now
    session.flush()

    return _flush_scope(
        session,
        UserEmployerScope(
            user_id=manager.id,
            enterprise_id=employer.enterprise_id,
            actual_employer_id=employer.id,
            responsibility_type="primary",
            granted_by=actor.id,
            assigned_at=now,
            status="active",
            created_at=now,
        ),
    )
