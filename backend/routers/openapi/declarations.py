"""开放平台·批量参停保申报（story-p2-write AC2–AC9、AC16）。

- 写端点强制 Idempotency-Key（AC2）：缺头 422，重放回放首次响应，异体 409。
- 批量申报独立限流 60 次/小时/key（AC16），两个申报端点共享一个 bucket——
  限的是「申报」这个动作的频率，不是某一个 URL。
- 业务校验与落库全部在 services/openapi/declarations.py（路由只做鉴权、
  幂等、限流、调 service、序列化——红线：不在路由里写业务规则）。
- 查询显式 WHERE enterprise_id = principal.enterprise_id；跨租户 404 模糊。
"""
import os
from typing import Optional

from fastapi import APIRouter, Depends, Request
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.orm import Session

from ...core.db import db
from ...models import OpenApiDeclaration, OpenApiDeclarationItem
from ...services.openapi.auth import OpenApiPrincipal, require_scope
from ...services.openapi.declarations import (
    accept_enrollment_declaration, accept_termination_declaration,
    serialize_declaration, serialize_declaration_item,
)
from ...services.openapi.idempotency import require_idempotency
from ...services.openapi.rate_limit import enforce_rate_limit
from .common import E_BIZ, OpenApiError, OpenApiRoute, ok, paginate

router = APIRouter(prefix="/openapi/v1", tags=["开放平台·参停保申报"],
                   route_class=OpenApiRoute)

DECLARATION_RATE_BUCKET = "declaration"
DECLARATION_RATE_WINDOW_SECONDS = 3600


def declaration_rate_limit_per_hour() -> int:
    return int(os.getenv("OPENAPI_DECLARATION_RATE_LIMIT_PER_HOUR", "60"))


class EnrollmentItemIn(BaseModel):
    name: str
    id_number: str
    id_type: str = "id_card"
    external_person_id: str = ""
    phone: str = ""
    position_id: Optional[int] = None
    expected_effective_at: Optional[str] = None


class EnrollmentDeclarationIn(BaseModel):
    items: list[EnrollmentItemIn]


class TerminationItemIn(BaseModel):
    person_id: Optional[int] = None
    id_number: str = ""
    external_person_id: str = ""
    expected_terminated_at: Optional[str] = None


class TerminationDeclarationIn(BaseModel):
    items: list[TerminationItemIn]


def _accepted_payload(declaration) -> dict:
    # AC3 契约：同步只承诺「受理」，不承诺生效（生效要等人工确认）。
    return {"declaration_id": declaration.id, "status": "accepted",
            "item_count": declaration.item_count}


@router.post("/enrollments")
async def create_enrollment_declaration(
        request: Request, payload: EnrollmentDeclarationIn,
        principal: OpenApiPrincipal = Depends(require_scope("declaration:write")),
        session: Session = Depends(db)):
    enforce_rate_limit(session, principal.key_id, DECLARATION_RATE_BUCKET,
                       declaration_rate_limit_per_hour(),
                       DECLARATION_RATE_WINDOW_SECONDS)
    ctx = await require_idempotency(principal, request)
    if ctx.replayed:
        return ok(ctx.snapshot, request.state.openapi_request_id)
    try:
        declaration = accept_enrollment_declaration(
            session, principal, [item.model_dump() for item in payload.items])
        data = _accepted_payload(declaration)
        ctx.save(session, data)
    except Exception:
        # review-p2 M3：service 只 flush 不 commit——失败时业务事务还占着
        # 写锁（SQLite 下 release 的独立会话删不动占位行），必须先回滚。
        session.rollback()
        ctx.release()   # 失败必须释放幂等占位，允许修正后同 key 重试
        raise
    return ok(data, request.state.openapi_request_id)


@router.post("/terminations")
async def create_termination_declaration(
        request: Request, payload: TerminationDeclarationIn,
        principal: OpenApiPrincipal = Depends(require_scope("declaration:write")),
        session: Session = Depends(db)):
    enforce_rate_limit(session, principal.key_id, DECLARATION_RATE_BUCKET,
                       declaration_rate_limit_per_hour(),
                       DECLARATION_RATE_WINDOW_SECONDS)
    ctx = await require_idempotency(principal, request)
    if ctx.replayed:
        return ok(ctx.snapshot, request.state.openapi_request_id)
    try:
        declaration = accept_termination_declaration(
            session, principal, [item.model_dump() for item in payload.items])
        data = _accepted_payload(declaration)
        ctx.save(session, data)
    except Exception:
        session.rollback()   # 先放掉业务事务，再删占位（见 enrollments 注释）
        ctx.release()
        raise
    return ok(data, request.state.openapi_request_id)


@router.get("/declarations")
def list_declarations(
        request: Request,
        kind: Optional[str] = None,
        status: Optional[str] = None,
        cursor: Optional[str] = None,
        limit: Optional[int] = None,
        principal: OpenApiPrincipal = Depends(require_scope("declaration:write")),
        session: Session = Depends(db)):
    query = session.query(OpenApiDeclaration).filter(
        OpenApiDeclaration.enterprise_id == principal.enterprise_id)
    if kind is not None:
        if kind not in ("enrollment", "termination"):
            raise OpenApiError(422, E_BIZ, "无效的 kind，应为 enrollment/termination")
        query = query.filter(OpenApiDeclaration.kind == kind)
    if status is not None:
        query = query.filter(OpenApiDeclaration.status == status)
    rows, next_cursor = paginate(query, cursor, limit)
    return ok({
        "items": [serialize_declaration(d) for d in rows],
        "next_cursor": next_cursor,
    }, request.state.openapi_request_id)


@router.get("/declarations/{declaration_id}")
def get_declaration(
        declaration_id: int, request: Request,
        principal: OpenApiPrincipal = Depends(require_scope("declaration:write")),
        session: Session = Depends(db)):
    declaration = session.get(OpenApiDeclaration, declaration_id)
    # 跨租户与不存在同答：404 模糊（AC8/AC17，与 P1 口径一致）。
    if declaration is None or declaration.enterprise_id != principal.enterprise_id:
        raise OpenApiError(404, E_BIZ, "资源不存在")
    items = session.scalars(
        select(OpenApiDeclarationItem).where(
            OpenApiDeclarationItem.declaration_id == declaration.id,
            # 防御性显式企业过滤（红线）。
            OpenApiDeclarationItem.enterprise_id == principal.enterprise_id)
        .order_by(OpenApiDeclarationItem.id.asc())).all()
    payload = serialize_declaration(declaration)
    payload["items"] = [serialize_declaration_item(item) for item in items]
    return ok(payload, request.state.openapi_request_id)
