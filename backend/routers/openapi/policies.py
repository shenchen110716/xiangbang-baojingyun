"""开放平台·保单（story-p1-readonly AC12/AC13）。

- 仅本企业：显式 WHERE enterprise_id = principal.enterprise_id；勘误 4——
  当前模型一张 policy 只属一个企业，但 members 仍按 enterprise_id 防御性
  过滤（防未来手工导入的保单）。
- 文件只走 download-url 间接模式（设计 §3.4）：POST download-url 返回
  300s 签名 URL（本地 file_tokens，S3 由 storage.resolve 换 presigned），
  任何端点都不直接回文件流。
"""
from typing import Optional

from fastapi import APIRouter, Depends, Request
from fastapi.responses import FileResponse, RedirectResponse
from sqlalchemy import select
from sqlalchemy.orm import Session

from ...core import storage
from ...core.db import db
from ...core.file_tokens import DEFAULT_TTL_SECONDS, make_download_token, verify_download_token
from ...models import InsuredPerson, Policy, PolicyMember
from ...services.openapi.audit import SENSITIVE_SCOPE, audit_sensitive
from ...services.openapi.auth import OpenApiPrincipal, require_scope
from ...services.openapi.serializers import (
    mask_id_number_openapi, serialize_policy,
)
from .common import E_BIZ, E_PERM, OpenApiError, OpenApiRoute, ok, paginate

router = APIRouter(prefix="/openapi/v1", tags=["开放平台·保单"],
                   route_class=OpenApiRoute)


def _get_own_policy(session: Session, principal: OpenApiPrincipal,
                    policy_id: int) -> Policy:
    policy = session.get(Policy, policy_id)
    # 跨租户与不存在同一回答（AC6）。
    if policy is None or policy.enterprise_id != principal.enterprise_id:
        raise OpenApiError(404, E_BIZ, "资源不存在")
    return policy


@router.get("/policies")
def list_policies(
        request: Request,
        status: Optional[str] = None,
        cursor: Optional[str] = None,
        limit: Optional[int] = None,
        principal: OpenApiPrincipal = Depends(require_scope("policy:read")),
        session: Session = Depends(db)):
    query = session.query(Policy).filter(
        Policy.enterprise_id == principal.enterprise_id)
    if status is not None:
        query = query.filter(Policy.status == status)
    rows, next_cursor = paginate(query, cursor, limit)
    return ok({
        "items": [serialize_policy(p) for p in rows],
        "next_cursor": next_cursor,
    }, request.state.openapi_request_id)


@router.get("/policies/{policy_id}")
def get_policy(
        policy_id: int, request: Request,
        principal: OpenApiPrincipal = Depends(require_scope("policy:read")),
        session: Session = Depends(db)):
    policy = _get_own_policy(session, principal, policy_id)
    return ok(serialize_policy(policy), request.state.openapi_request_id)


@router.post("/policies/{policy_id}/download-url")
def policy_download_url(
        policy_id: int, request: Request,
        principal: OpenApiPrincipal = Depends(require_scope("policy:read")),
        session: Session = Depends(db)):
    policy = _get_own_policy(session, principal, policy_id)
    if not policy.document_url:
        # 无保单文件是业务态，不是"资源不存在"：42200（AC12）。
        raise OpenApiError(422, E_BIZ, "该保单暂无可下载的保单文件")
    token, expires = make_download_token(f"openapi-policy-document:{policy.id}",
                                         DEFAULT_TTL_SECONDS)
    return ok({
        "download_url": (f"/openapi/v1/policy-files/{policy.id}"
                         f"?token={token}&expires={expires}"),
        "expires_at": expires,
        "document_name": policy.document_name or None,
    }, request.state.openapi_request_id)


@router.get("/policy-files/{policy_id}", include_in_schema=False)
def download_policy_file(policy_id: int, token: str, expires: int,
                         session: Session = Depends(db)):
    """签名 URL 兑付：token 即授权（AC12），过期即拒，不直接回流大文件
    ——本地走 FileResponse，S3 由 resolve 换 120s presigned 重定向。"""
    if not verify_download_token(f"openapi-policy-document:{policy_id}",
                                 expires, token):
        raise OpenApiError(403, E_PERM, "下载链接无效或已过期")
    policy = session.get(Policy, policy_id)
    if not policy or not policy.document_url:
        raise OpenApiError(404, E_BIZ, "资源不存在")
    resolved = storage.resolve(policy.document_url,
                               filename=policy.document_name or None)
    if not resolved:
        raise OpenApiError(404, E_BIZ, "资源不存在")
    kind, ref = resolved
    if kind == "redirect":
        return RedirectResponse(ref)
    return FileResponse(ref, filename=policy.document_name or None)


@router.get("/policies/{policy_id}/members")
def policy_members(
        policy_id: int, request: Request,
        cursor: Optional[str] = None,
        limit: Optional[int] = None,
        principal: OpenApiPrincipal = Depends(require_scope("policy:read")),
        session: Session = Depends(db)):
    policy = _get_own_policy(session, principal, policy_id)
    sensitive = SENSITIVE_SCOPE in principal.scopes
    query = session.query(PolicyMember).filter(
        PolicyMember.policy_id == policy.id,
        # 勘误 4：policy 已按企业校验，这里仍显式按 enterprise_id 过滤
        # 成员（防御未来手工导入的跨企业保单）。
        PolicyMember.person_id.in_(
            select(InsuredPerson.id).where(
                InsuredPerson.enterprise_id == principal.enterprise_id)))
    rows, next_cursor = paginate(query, cursor, limit)
    # 批量取人员（review-p1 m3：原先逐 member 一次 session.get，上限 200 次）。
    persons = {
        p.id: p for p in session.scalars(
            select(InsuredPerson).where(
                InsuredPerson.id.in_([m.person_id for m in rows]),
                InsuredPerson.enterprise_id == principal.enterprise_id))
    } if rows else {}
    items = []
    for member in rows:
        person = persons.get(member.person_id)
        items.append({
            "person_id": member.person_id,
            "name": person.name if person else None,
            "id_number": (person.id_number if sensitive
                          else mask_id_number_openapi(person.id_number))
            if person else None,
            "status": member.status,
            "effective_at": member.effective_at.isoformat()
            if member.effective_at else None,
            "terminated_at": member.terminated_at.isoformat()
            if member.terminated_at else None,
            "endorsement_no": member.endorsement_no or None,
        })
    # 审计放序列化之后：audit 的 commit 会使已加载行过期（review-p1 m3）。
    if sensitive and rows:
        audit_sensitive(session, principal, request,
                        f"policy-members policy={policy.id} "
                        f"persons={','.join(str(m.person_id) for m in rows)}")
    return ok({"policy_id": policy.id, "items": items,
               "next_cursor": next_cursor}, request.state.openapi_request_id)
