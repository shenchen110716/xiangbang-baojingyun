"""开放平台元端点：ping（AC2）与 scope-probe（AC5 联调探针）。"""
from datetime import datetime, timezone
from typing import Optional

from fastapi import APIRouter, Depends, Request

from ...services.openapi.auth import OpenApiPrincipal, require_scope
from .common import OpenApiRoute, ok

router = APIRouter(prefix="/openapi/v1", tags=["开放平台"],
                   route_class=OpenApiRoute)


@router.get("/ping")
def ping(request: Request, demo: Optional[int] = None,
         principal: OpenApiPrincipal = Depends(require_scope())):
    """联通性探针：任何有效 key 可调；回显身份与环境，不含业务数据。"""
    return ok({
        "pong": True,
        "demo": demo,
        "enterprise_id": principal.enterprise_id,
        "env": principal.env,
        "key_id": principal.key_id,
        "server_time": datetime.now(timezone.utc).isoformat(),
    }, request.state.openapi_request_id)


@router.get("/scope-probe")
def scope_probe(request: Request,
                principal: OpenApiPrincipal = Depends(require_scope("probe:read"))):
    """带 scope 的最小端点：验证 403/40300 路径（AC5），无业务数据。"""
    return ok({
        "probed": True,
        "enterprise_id": principal.enterprise_id,
    }, request.state.openapi_request_id)
