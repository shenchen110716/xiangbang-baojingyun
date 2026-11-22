"""开放平台·webhook 回调地址管理（story-p2-write AC12）。

- 注册要求 https 且指向公网（私网/内网段 422，review-p2 M6）；http 仅限
  loopback 且仅当 OPENAPI_WEBHOOK_ALLOW_INSECURE=1（本地联调/smoke 用）。
- 按（企业, URL）去重（review-p2 M4）：重复注册返回已有 endpoint（幂等）。
- 按企业隔离：列表/删除/测试都显式 WHERE enterprise_id = principal.
  enterprise_id，跨租户 404 模糊。
- POST {id}/test 投递 webhook.test 事件（只投该 endpoint），走同一条
  outbox 投递循环——测试的就是真实投递链路（签名、退避、超时）。
"""
from fastapi import APIRouter, Depends, Request
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.orm import Session

from ...core.db import db
from ...models import OpenApiWebhookEndpoint
from ...services.openapi.auth import OpenApiPrincipal, require_scope
from ...services.openapi.webhook_outbox import (
    emit_test_event, is_acceptable_webhook_url,
)
from .common import E_BIZ, OpenApiError, OpenApiRoute, ok

router = APIRouter(prefix="/openapi/v1", tags=["开放平台·Webhook"],
                   route_class=OpenApiRoute)

MAX_ENDPOINTS_PER_ENTERPRISE = 10


class WebhookIn(BaseModel):
    url: str


def _serialize_endpoint(endpoint: OpenApiWebhookEndpoint) -> dict:
    return {
        "id": endpoint.id,
        "url": endpoint.url,
        "status": endpoint.status,
        "created_at": (endpoint.created_at.isoformat()
                       if endpoint.created_at else None),
    }


def _get_own_endpoint(session: Session, principal: OpenApiPrincipal,
                      endpoint_id: int) -> OpenApiWebhookEndpoint:
    endpoint = session.get(OpenApiWebhookEndpoint, endpoint_id)
    if endpoint is None or endpoint.enterprise_id != principal.enterprise_id:
        raise OpenApiError(404, E_BIZ, "资源不存在")
    return endpoint


@router.post("/webhooks")
def register_webhook(
        payload: WebhookIn, request: Request,
        principal: OpenApiPrincipal = Depends(require_scope("webhook:manage")),
        session: Session = Depends(db)):
    url = payload.url.strip()
    if not is_acceptable_webhook_url(url):
        raise OpenApiError(422, E_BIZ,
                           "回调地址必须为 https 且指向公网地址（私网/内网段不受理）")
    # review-p2 M4：按（企业, URL）去重——重复注册直接返回已有 endpoint，
    # POST /webhooks 由此天然幂等（文档口径与实现一致）。
    existing = session.scalar(select(OpenApiWebhookEndpoint).where(
        OpenApiWebhookEndpoint.enterprise_id == principal.enterprise_id,
        OpenApiWebhookEndpoint.url == url).limit(1))
    if existing is not None:
        return ok(_serialize_endpoint(existing), request.state.openapi_request_id)
    count = len(session.scalars(select(OpenApiWebhookEndpoint.id).where(
        OpenApiWebhookEndpoint.enterprise_id == principal.enterprise_id)).all())
    if count >= MAX_ENDPOINTS_PER_ENTERPRISE:
        raise OpenApiError(422, E_BIZ,
                           f"每个企业最多注册 {MAX_ENDPOINTS_PER_ENTERPRISE} 个回调地址")
    endpoint = OpenApiWebhookEndpoint(
        enterprise_id=principal.enterprise_id, url=url,
        key_id=principal.key_id, status="active")
    session.add(endpoint)
    session.commit()
    return ok(_serialize_endpoint(endpoint), request.state.openapi_request_id)


@router.get("/webhooks")
def list_webhooks(
        request: Request,
        principal: OpenApiPrincipal = Depends(require_scope("webhook:manage")),
        session: Session = Depends(db)):
    endpoints = session.scalars(
        select(OpenApiWebhookEndpoint).where(
            OpenApiWebhookEndpoint.enterprise_id == principal.enterprise_id)
        .order_by(OpenApiWebhookEndpoint.id.asc())).all()
    return ok({"items": [_serialize_endpoint(e) for e in endpoints]},
              request.state.openapi_request_id)


@router.delete("/webhooks/{endpoint_id}")
def delete_webhook(
        endpoint_id: int, request: Request,
        principal: OpenApiPrincipal = Depends(require_scope("webhook:manage")),
        session: Session = Depends(db)):
    endpoint = _get_own_endpoint(session, principal, endpoint_id)
    session.delete(endpoint)
    session.commit()
    return ok({"deleted_id": endpoint_id}, request.state.openapi_request_id)


@router.post("/webhooks/{endpoint_id}/test")
def test_webhook(
        endpoint_id: int, request: Request,
        principal: OpenApiPrincipal = Depends(require_scope("webhook:manage")),
        session: Session = Depends(db)):
    endpoint = _get_own_endpoint(session, principal, endpoint_id)
    delivery = emit_test_event(session, endpoint)
    session.commit()
    return ok({"event_id": delivery.event_id, "status": delivery.status},
              request.state.openapi_request_id)
