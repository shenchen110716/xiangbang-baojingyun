"""对外开放平台路由族 /openapi/v1/*（签名 v2 + 统一包）。

所有子路由必须：route_class=OpenApiRoute（统一包 + 请求日志）、依赖
services.openapi.auth.require_scope（签名/IP/限流/scope）、查询显式
WHERE enterprise_id = principal.enterprise_id（AC16 红线）。后续 story 的
insured/declaration/account 路由加到这里 include。
"""
from fastapi import APIRouter
from fastapi.openapi.utils import get_openapi

from .common import E_BIZ, OpenApiError, OpenApiRoute
from .accounts import router as accounts_router
from .insured import router as insured_router
from .meta import router as meta_router
from .policies import router as policies_router

router = APIRouter()
router.include_router(meta_router)
router.include_router(insured_router)
router.include_router(policies_router)
router.include_router(accounts_router)

# ---------------------------------------------------------------- AC15
# /openapi/v1 的 OpenAPI schema 与内部 /api 的 /docs 分开暴露：这里只收
# 开放路由组（router.routes，签名端点自身与兜底路由 include_in_schema=
# False 自动剔除）；/api 面的 schema 在 app.py 里反向排除 /openapi/v1。
_schema_router = APIRouter(route_class=OpenApiRoute)
_openapi_schema_cache: dict = {}


def build_openapi_schema() -> dict:
    if not _openapi_schema_cache:
        _openapi_schema_cache.update(get_openapi(
            title="无忧保开放平台 API",
            version="1.0.0",
            description="企业客户 server-to-server 开放接口（/openapi/v1，"
                        "签名 v2）。接入说明见《无忧保开放平台 API 接口使用说明》。",
            routes=router.routes,
        ))
    return _openapi_schema_cache


@_schema_router.get("/openapi/v1/openapi.json", include_in_schema=False)
def openapi_v1_schema():
    """开放平台独立 schema（AC15）：无需签名即可读，便于对接方生成客户端；
    只含 /openapi/v1 路由组，不含任何内部 /api 面。"""
    return build_openapi_schema()


router.include_router(_schema_router)

# /openapi/v1 下未匹配路径的兜底：没有它，404 会走 FastAPI 默认的
# {"detail": "Not Found"}，不符合统一包契约（review-p0 minor-5）。
# 注意必须在业务子路由之后 include，FastAPI 按注册顺序匹配。
_fallback = APIRouter(prefix="/openapi/v1", route_class=OpenApiRoute)


@_fallback.api_route("/{_unmatched:path}",
                     methods=["GET", "POST", "PUT", "PATCH", "DELETE"],
                     include_in_schema=False)
async def _openapi_not_found(_unmatched: str):
    raise OpenApiError(404, E_BIZ, "接口不存在")


router.include_router(_fallback)
