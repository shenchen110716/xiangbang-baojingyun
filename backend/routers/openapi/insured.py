"""开放平台·参保人员名单（story-p1-readonly AC2–AC8）。

- 每条查询显式 WHERE enterprise_id = principal.enterprise_id（红线）；
  跨租户访问一律 404「资源不存在」，不泄漏存在性（AC6）。
- 证件号默认脱敏（前 3 + * + 后 4）；key 具 insured:read_sensitive 时
  同端点返回明文，且每次明文响应写 audit_logs（user_id=NULL，detail 带
  key_id/request_id，沿用 §7.3 机器调用的审计姿态）。
- 导出为异步任务：入队即返回 task_id，后台循环生成 XLSX；完成后经
  export-tasks/{id} 换短时签名下载链接（TTL 300s），文件不直接回流。
- 明文口径任务的权限钉在每次换链接（review-p1 M2）：params_json 固化
  sensitive=true 的任务，换下载链接时要求当前 key **仍**持有
  insured:read_sensitive——key 被降权后旧明文文件立即不可再续取；每次
  发放明文链接写审计。
"""
import json
import os
from typing import Optional

from fastapi import APIRouter, Depends, Request
from fastapi.responses import FileResponse, RedirectResponse
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.orm import Session

from ...core import storage
from ...core.db import db
from ...core.file_tokens import DEFAULT_TTL_SECONDS, make_download_token, verify_download_token
from ...models import ExportTask, InsuredPerson, ParticipationOperation
from ...services.openapi.audit import SENSITIVE_SCOPE, audit_sensitive
from ...services.openapi.auth import OpenApiPrincipal, require_scope
from ...services.openapi.export_tasks import enqueue_export
from ...services.openapi.rate_limit import enforce_rate_limit
from ...services.openapi.serializers import (
    current_coverage, export_params, insured_persons_query,
    parse_updated_since, serialize_insured_person, serialize_operation,
)
from .common import E_BIZ, E_PERM, OpenApiError, OpenApiRoute, ok, paginate

router = APIRouter(prefix="/openapi/v1", tags=["开放平台·参保名单"],
                   route_class=OpenApiRoute)

EXPORT_RATE_BUCKET = "export"
EXPORT_RATE_WINDOW_SECONDS = 3600


def export_rate_limit_per_hour() -> int:
    return int(os.getenv("OPENAPI_EXPORT_RATE_LIMIT_PER_HOUR", "10"))


def _get_own_person(session: Session, principal: OpenApiPrincipal,
                    person_id: int) -> InsuredPerson:
    person = session.get(InsuredPerson, person_id)
    # 跨租户与不存在同一回答：404 模糊，不泄漏存在性（AC6）。
    if person is None or person.enterprise_id != principal.enterprise_id:
        raise OpenApiError(404, E_BIZ, "资源不存在")
    return person


@router.get("/insured-persons")
def list_insured_persons(
        request: Request,
        status: Optional[str] = None,
        position_id: Optional[int] = None,
        updated_since: Optional[str] = None,
        cursor: Optional[str] = None,
        limit: Optional[int] = None,
        principal: OpenApiPrincipal = Depends(require_scope("insured:read")),
        session: Session = Depends(db)):
    sensitive = SENSITIVE_SCOPE in principal.scopes
    query = insured_persons_query(
        session, principal.enterprise_id, status=status,
        position_id=position_id,
        updated_since=parse_updated_since(updated_since))
    rows, next_cursor = paginate(query, cursor, limit)
    # 先序列化再审计：audit 的 commit 会使已加载行过期，放在前面会让随后
    # 的逐行序列化触发逐行 re-SELECT（review-p1 m3）。
    items = [serialize_insured_person(p, sensitive=sensitive) for p in rows]
    if sensitive and rows:
        audit_sensitive(session, principal, request,
                        f"list persons={','.join(str(p.id) for p in rows)}")
    return ok({
        "items": items,
        "next_cursor": next_cursor,
    }, request.state.openapi_request_id)


@router.get("/insured-persons/{person_id}")
def get_insured_person(
        person_id: int, request: Request,
        principal: OpenApiPrincipal = Depends(require_scope("insured:read")),
        session: Session = Depends(db)):
    person = _get_own_person(session, principal, person_id)
    sensitive = SENSITIVE_SCOPE in principal.scopes
    payload = serialize_insured_person(person, sensitive=sensitive)
    payload["coverage"] = current_coverage(session, person)
    if sensitive:
        audit_sensitive(session, principal, request, f"detail person={person.id}")
    return ok(payload, request.state.openapi_request_id)


@router.get("/insured-persons/{person_id}/history")
def insured_person_history(
        person_id: int, request: Request,
        principal: OpenApiPrincipal = Depends(require_scope("insured:read")),
        session: Session = Depends(db)):
    person = _get_own_person(session, principal, person_id)
    operations = session.scalars(
        select(ParticipationOperation).where(
            ParticipationOperation.person_id == person.id,
            # person 已按企业校验过，这里仍显式再过滤一次（防御性，红线）。
            ParticipationOperation.enterprise_id == principal.enterprise_id)
        .order_by(ParticipationOperation.id.desc())).all()
    return ok({
        "person_id": person.id,
        "items": [serialize_operation(op) for op in operations],
    }, request.state.openapi_request_id)


class ExportIn(BaseModel):
    status: Optional[str] = None
    position_id: Optional[int] = None
    updated_since: Optional[str] = None


@router.post("/insured-persons/export")
def export_insured_persons(
        request: Request, payload: Optional[ExportIn] = None,
        principal: OpenApiPrincipal = Depends(require_scope("insured:read")),
        session: Session = Depends(db)):
    # 导出独立限流：10 次/小时/key（AC8），复用 P0 固定窗口原语。
    enforce_rate_limit(session, principal.key_id, EXPORT_RATE_BUCKET,
                       export_rate_limit_per_hour(), EXPORT_RATE_WINDOW_SECONDS)
    payload = payload or ExportIn()
    # 过滤条件入队前先校验（非法 status/updated_since 当场 422，而不是
    # 让任务在后台失败）。
    parse_updated_since(payload.updated_since)
    sensitive = SENSITIVE_SCOPE in principal.scopes
    insured_persons_query(session, principal.enterprise_id,
                          status=payload.status,
                          position_id=payload.position_id)
    params = export_params(status=payload.status,
                           position_id=payload.position_id,
                           updated_since=payload.updated_since,
                           sensitive=sensitive)
    task = enqueue_export(session, principal, "insured_persons", params)
    if sensitive:
        audit_sensitive(session, principal, request,
                        f"export task={task.id}（明文口径）")
    return ok({"task_id": task.id, "status": task.status},
              request.state.openapi_request_id)


def _get_own_task(session: Session, principal: OpenApiPrincipal,
                  task_id: int) -> ExportTask:
    task = session.get(ExportTask, task_id)
    if task is None or task.enterprise_id != principal.enterprise_id:
        raise OpenApiError(404, E_BIZ, "资源不存在")
    return task


@router.get("/export-tasks/{task_id}")
def get_export_task(
        task_id: int, request: Request,
        principal: OpenApiPrincipal = Depends(require_scope("insured:read")),
        session: Session = Depends(db)):
    task = _get_own_task(session, principal, task_id)
    # review-p1 M2：明文口径任务要求当前 key 仍持有 read_sensitive——
    # scope 被撤销（数据安全承诺撤销）后，旧明文任务立即不可再查/续取。
    try:
        sensitive_task = bool(json.loads(task.params_json or "{}").get("sensitive"))
    except ValueError:
        sensitive_task = True   # 参数损坏时按最严格口径处理
    if sensitive_task and SENSITIVE_SCOPE not in principal.scopes:
        raise OpenApiError(403, E_PERM, "该导出任务为明文口径，当前密钥无权访问")
    payload = {
        "task_id": task.id,
        "task_type": task.task_type,
        "status": task.status,
        "created_at": task.created_at.isoformat() if task.created_at else None,
        "finished_at": task.finished_at.isoformat() if task.finished_at else None,
        # error 只在终态失败/过期时对外（review-p1 M3 的内部重试标记不外泄）。
        "error": task.error or None if task.status in ("failed", "expired") else None,
        "download_url": None,
        "download_expires_at": None,
    }
    if task.status == "done" and task.file_url:
        token, expires = make_download_token(f"openapi-export:{task.id}",
                                             DEFAULT_TTL_SECONDS)
        payload["download_url"] = (f"/openapi/v1/export-files/{task.id}"
                                   f"?token={token}&expires={expires}")
        payload["download_expires_at"] = expires
        if sensitive_task:
            # 每次发放明文文件链接都留痕（review-p1 M2）。
            audit_sensitive(session, principal, request,
                            f"export-download-url task={task.id}")
    return ok(payload, request.state.openapi_request_id)


@router.get("/export-files/{task_id}", include_in_schema=False)
def download_export_file(task_id: int, token: str, expires: int,
                         session: Session = Depends(db)):
    """短时签名下载（AC7）：token 即授权，过期自动失效；不走签名头。"""
    if not verify_download_token(f"openapi-export:{task_id}", expires, token):
        raise OpenApiError(403, E_PERM, "下载链接无效或已过期")
    task = session.get(ExportTask, task_id)
    if not task or task.status != "done" or not task.file_url:
        raise OpenApiError(404, E_BIZ, "资源不存在")
    resolved = storage.resolve(task.file_url,
                               filename=f"insured-export-{task.id}.xlsx")
    if not resolved:
        raise OpenApiError(404, E_BIZ, "资源不存在")
    kind, ref = resolved
    if kind == "redirect":
        return RedirectResponse(ref)
    return FileResponse(ref, filename=f"insured-export-{task.id}.xlsx")
